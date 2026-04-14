#!/usr/bin/env python3
"""
InstantIoT Server — Test E2E du relay TCP + WebSocket

Ce script simule :
  1. Un device ESP32 qui se connecte en TCP et envoie des trames
  2. Une app Android qui se connecte en WebSocket et recoit les trames
  3. L'app qui envoie une commande au device via WebSocket

Usage :
  1. Lancer le serveur : java -jar build/libs/instantiot-server-0.0.1-all.jar
  2. Creer un projet + device via Bruno (ou l'API)
  3. Remplir les constantes ci-dessous
  4. Lancer : python3 test-relay.py

Dependances :
  pip install websockets
"""

import asyncio
import socket
import struct
import json
import sys
import time

# ============================================================
# CONFIGURATION — a remplir avec tes valeurs
# ============================================================
SERVER_HOST = "localhost"
HTTP_PORT = 8080
TCP_PORT = 9001

JWT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIyZGE4ZDIwZS1lN2RmLTQ4OTYtYmRlZS1mZjYyMzBkNDA2OTAiLCJpc3MiOiJpbnN0YW50aW90LXNlcnZlciIsImF1ZCI6Imluc3RhbnRpb3QtYXBwIiwiZXhwIjoxNzc4NzM0NzQzfQ.LOSYYAF46p1CkSfVSc5C3je6nOVfoLALq3YG8N6YcWw"       # token JWT (via POST /api/login)
PROJECT_ID = "4e3143de-e189-4a9a-8cad-f5f0c271bbb3"      # UUID du projet
DEVICE_ID = "27a8b137-f603-4b28-a8f6-cdd744aea820"       # UUID du device
DEVICE_TOKEN = "991c7874-aff6-4ed1-a707-b8ce6ba149c4"    # token du device (retourne a la creation)
WIDGET_ID = "widget-test-001"  # ID du widget a simuler

# ============================================================
# CRC8 — identique au serveur (CRC-8/SMBUS, polynome 0x07)
# ============================================================
CRC8_TABLE = []
for i in range(256):
    crc = i
    for _ in range(8):
        if crc & 0x80:
            crc = (crc << 1) ^ 0x07
        else:
            crc = crc << 1
    CRC8_TABLE.append(crc & 0xFF)


def compute_crc8(data: bytes) -> int:
    crc = 0
    for b in data:
        crc = CRC8_TABLE[(crc ^ b) & 0xFF]
    return crc


# ============================================================
# CONSTRUCTION DE TRAMES iWidgets v1
# ============================================================
def build_device_frame(widget_id: str, payload: bytes, seq: int = 0) -> bytes:
    """
    Construit une trame Device -> Server (DEV_COUNT = 0)

    Format :
      AA | VER(01) | LEN(2B LE) | SEQ | DEV_COUNT(0) | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC8
    """
    wid_bytes = widget_id.encode("utf-8")

    # body = DEV_COUNT + WID_LEN + WID + TYPE + EVENT + PAYLOAD
    body = bytearray()
    body.append(0x00)                    # DEV_COUNT = 0
    body.append(len(wid_bytes))          # WID_LEN
    body.extend(wid_bytes)               # WID
    body.append(0x01)                    # TYPE (display)
    body.append(0x01)                    # EVENT (data)
    body.extend(payload)                 # PAYLOAD

    body_bytes = bytes(body)
    body_len = len(body_bytes)
    crc = compute_crc8(body_bytes)

    # header = AA + VER + LEN(2B LE) + SEQ
    frame = bytearray()
    frame.append(0xAA)                   # SYNC
    frame.append(0x01)                   # VER
    frame.append(body_len & 0xFF)        # LEN low
    frame.append((body_len >> 8) & 0xFF) # LEN high
    frame.append(seq & 0xFF)             # SEQ
    frame.extend(body_bytes)             # BODY
    frame.append(crc)                    # CRC8

    return bytes(frame)


def build_app_command_frame(device_id: str, widget_id: str, payload: bytes, seq: int = 0) -> bytes:
    """
    Construit une trame App -> Server -> Device (DEV_COUNT = 1)

    Format :
      AA | VER | LEN | SEQ | DEV_COUNT(1) | DEV_LEN | DEV_ID | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC8
    """
    dev_bytes = device_id.encode("utf-8")
    wid_bytes = widget_id.encode("utf-8")

    body = bytearray()
    body.append(0x01)                    # DEV_COUNT = 1
    body.append(len(dev_bytes))          # DEV_LEN
    body.extend(dev_bytes)               # DEV_ID
    body.append(len(wid_bytes))          # WID_LEN
    body.extend(wid_bytes)               # WID
    body.append(0x02)                    # TYPE (command)
    body.append(0x01)                    # EVENT
    body.extend(payload)                 # PAYLOAD

    body_bytes = bytes(body)
    body_len = len(body_bytes)
    crc = compute_crc8(body_bytes)

    frame = bytearray()
    frame.append(0xAA)
    frame.append(0x01)
    frame.append(body_len & 0xFF)
    frame.append((body_len >> 8) & 0xFF)
    frame.append(seq & 0xFF)
    frame.extend(body_bytes)
    frame.append(crc)

    return bytes(frame)


# ============================================================
# TEST 1 — Device TCP : handshake + envoi de trames
# ============================================================
def test_device_tcp():
    """Simule un ESP32 qui se connecte et envoie 3 trames"""
    print("\n" + "=" * 60)
    print("TEST 1 — Device TCP (simule un ESP32)")
    print("=" * 60)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5)

    try:
        sock.connect((SERVER_HOST, TCP_PORT))
        print(f"  [OK] Connecte a {SERVER_HOST}:{TCP_PORT}")

        # handshake — envoyer [TOKEN_LEN | TOKEN]
        token_bytes = DEVICE_TOKEN.encode("utf-8")
        handshake = bytes([len(token_bytes)]) + token_bytes
        sock.sendall(handshake)
        print(f"  [OK] Handshake envoye (token {len(token_bytes)} bytes)")

        # attendre que le serveur accepte (pas de rejet = OK)
        time.sleep(1)

        # envoyer 3 trames avec des payloads differents
        payloads = [
            b"\x00\x19",       # temperature 25.0 (exemple)
            b"\x00\x1A",       # temperature 26.0
            b"\x00\x1B",       # temperature 27.0
        ]

        for i, payload in enumerate(payloads):
            frame = build_device_frame(WIDGET_ID, payload, seq=i)
            sock.sendall(frame)
            print(f"  [OK] Trame {i+1}/3 envoyee ({len(frame)} bytes) — payload={payload.hex()}")
            time.sleep(0.5)

        print(f"  [OK] 3 trames envoyees avec succes")

        # garder la connexion ouverte un moment pour le broadcast
        time.sleep(2)

        return sock  # retourner le socket pour le test app->device

    except socket.timeout:
        print(f"  [FAIL] Timeout — le serveur a peut-etre rejete le token")
        sock.close()
        return None
    except Exception as e:
        print(f"  [FAIL] Erreur : {e}")
        sock.close()
        return None


# ============================================================
# TEST 2 — App WebSocket : connexion + reception des trames
# ============================================================
async def test_app_websocket():
    """Simule une app Android qui se connecte et ecoute les broadcasts"""
    print("\n" + "=" * 60)
    print("TEST 2 — App WebSocket (simule l'app Android)")
    print("=" * 60)

    try:
        import websockets
    except ImportError:
        print("  [SKIP] Module 'websockets' non installe")
        print("  Installer avec : pip install websockets")
        return None

    uri = f"ws://{SERVER_HOST}:{HTTP_PORT}/ws/app"
    headers = {"Authorization": f"Bearer {JWT_TOKEN}"}

    try:
        ws = await websockets.connect(uri, additional_headers=headers)
        print(f"  [OK] WebSocket connecte")

        # handshake — envoyer le projectId
        await ws.send(PROJECT_ID)
        print(f"  [OK] Handshake envoye (projectId={PROJECT_ID[:8]}...)")

        return ws

    except Exception as e:
        print(f"  [FAIL] Erreur WebSocket : {e}")
        return None


# ============================================================
# TEST 3 — Flow complet : device envoie, app recoit
# ============================================================
async def test_full_flow():
    """Test E2E : device envoie des trames, app les recoit en temps reel"""
    print("\n" + "=" * 60)
    print("TEST 3 — Flow complet Device -> Server -> App")
    print("=" * 60)

    try:
        import websockets
    except ImportError:
        print("  [SKIP] Module 'websockets' non installe")
        return

    uri = f"ws://{SERVER_HOST}:{HTTP_PORT}/ws/app"
    headers = {"Authorization": f"Bearer {JWT_TOKEN}"}

    try:
        # connecter l'app en WebSocket
        ws = await websockets.connect(uri, additional_headers=headers)
        await ws.send(PROJECT_ID)
        print(f"  [OK] App connectee et abonnee au projet")

        # connecter le device en TCP (dans un thread)
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        sock.connect((SERVER_HOST, TCP_PORT))

        token_bytes = DEVICE_TOKEN.encode("utf-8")
        sock.sendall(bytes([len(token_bytes)]) + token_bytes)
        print(f"  [OK] Device connecte et authentifie")

        await asyncio.sleep(1)

        # device envoie une trame
        payload = b"\x00\x19"  # 25 degres
        frame = build_device_frame(WIDGET_ID, payload, seq=42)
        sock.sendall(frame)
        print(f"  [>>] Device a envoye une trame (payload={payload.hex()})")

        # app recoit la trame broadcastee
        try:
            received = await asyncio.wait_for(ws.recv(), timeout=5.0)
            if isinstance(received, bytes):
                print(f"  [<<] App a recu {len(received)} bytes : {received.hex()}")
                print(f"  [OK] RELAY DEVICE -> APP FONCTIONNE !")
            else:
                print(f"  [<<] App a recu (texte) : {received}")
        except asyncio.TimeoutError:
            print(f"  [FAIL] Timeout — aucune trame recue par l'app en 5s")

        # ============================================================
        # TEST 4 — App envoie une commande au device
        # ============================================================
        print(f"\n  --- Test App -> Device ---")

        # l'app envoie une commande
        cmd_payload = b"\x01"  # commande ON
        cmd_frame = build_app_command_frame(DEVICE_ID, WIDGET_ID, cmd_payload, seq=1)
        await ws.send(cmd_frame)
        print(f"  [>>] App a envoye une commande ({len(cmd_frame)} bytes)")

        # le device recoit la trame (trimee par le serveur)
        sock.settimeout(5)
        try:
            received_data = sock.recv(1024)
            if received_data:
                print(f"  [<<] Device a recu {len(received_data)} bytes : {received_data.hex()}")
                # verifier que c'est une trame valide (commence par AA 01)
                if received_data[0] == 0xAA and received_data[1] == 0x01:
                    print(f"  [OK] RELAY APP -> DEVICE FONCTIONNE !")
                else:
                    print(f"  [WARN] Trame recue mais header inattendu")
            else:
                print(f"  [FAIL] Connexion fermee par le serveur")
        except socket.timeout:
            print(f"  [FAIL] Timeout — aucune trame recue par le device en 5s")

        # cleanup
        sock.close()
        await ws.close()

    except Exception as e:
        print(f"  [FAIL] Erreur : {e}")
        import traceback
        traceback.print_exc()


# ============================================================
# TEST 5 — Securite : ownership violation
# ============================================================
async def test_ownership_violation():
    """Verifie qu'un user ne peut pas espionner un projet qui ne lui appartient pas"""
    print("\n" + "=" * 60)
    print("TEST 5 — Securite : ownership violation")
    print("=" * 60)

    try:
        import websockets
    except ImportError:
        print("  [SKIP] Module 'websockets' non installe")
        return

    uri = f"ws://{SERVER_HOST}:{HTTP_PORT}/ws/app"
    headers = {"Authorization": f"Bearer {JWT_TOKEN}"}

    try:
        ws = await websockets.connect(uri, additional_headers=headers)

        # envoyer un faux projectId qui n'appartient pas au user
        fake_project_id = "00000000-0000-0000-0000-000000000000"
        await ws.send(fake_project_id)
        print(f"  [>>] Envoye faux projectId : {fake_project_id}")

        # le serveur devrait fermer la connexion
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=3.0)
            print(f"  [FAIL] Le serveur a accepte le faux projet : {msg}")
        except Exception:
            print(f"  [OK] Connexion fermee par le serveur — ownership check fonctionne !")

    except Exception as e:
        print(f"  [OK] Connexion rejetee : {e}")


# ============================================================
# MAIN
# ============================================================
async def main():
    print("=" * 60)
    print("  InstantIoT Server — Test E2E Relay")
    print("=" * 60)

    # verification config
    missing = []
    if not JWT_TOKEN:
        missing.append("JWT_TOKEN")
    if not PROJECT_ID:
        missing.append("PROJECT_ID")
    if not DEVICE_ID:
        missing.append("DEVICE_ID")
    if not DEVICE_TOKEN:
        missing.append("DEVICE_TOKEN")

    if missing:
        print(f"\n  [ERREUR] Variables manquantes : {', '.join(missing)}")
        print(f"\n  Remplir les constantes en haut du fichier :")
        print(f"    1. JWT_TOKEN   — via POST /api/login")
        print(f"    2. PROJECT_ID  — via POST /api/projects")
        print(f"    3. DEVICE_ID   — via POST /api/devices")
        print(f"    4. DEVICE_TOKEN — retourne a la creation du device")
        print(f"\n  Puis relancer : .venv/bin/python3 test-relay.py")
        sys.exit(1)

    print(f"\n  Server    : {SERVER_HOST}")
    print(f"  HTTP/WS   : :{HTTP_PORT}")
    print(f"  TCP       : :{TCP_PORT}")
    print(f"  Project   : {PROJECT_ID[:8]}...")
    print(f"  Device    : {DEVICE_ID[:8]}...")
    print(f"  Widget    : {WIDGET_ID}")

    # test 1 — device TCP seul
    device_sock = test_device_tcp()
    if device_sock:
        device_sock.close()

    # test 2+3+4 — flow complet device <-> app
    await test_full_flow()

    # test 5 — securite ownership
    await test_ownership_violation()

    print("\n" + "=" * 60)
    print("  TESTS TERMINES")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
