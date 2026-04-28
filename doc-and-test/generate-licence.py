#!/usr/bin/env python3
"""
InstantIoT — Licence Key Generator

Genere des cles de licence signees (JWT RS256) pour les clients.
Ce script reste chez toi — NE PAS distribuer avec le serveur.

Usage:
  # Premier lancement — genere la paire de cles RSA
  python3 generate-licence.py --generate-keys

  # Licence lifetime (defaut V1 maker) — pas d'expiration
  python3 generate-licence.py

  # Licence avec expiration (trial, beta tests, demo)
  python3 generate-licence.py --expires 2027-01-01

  # ID custom (sinon auto-genere INST-XXXX-XXXX-XXXX)
  python3 generate-licence.py --id INST-CLIENT-001-DEMO

Dependances:
  pip install PyJWT cryptography
"""

import argparse
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

try:
    import jwt
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
except ImportError:
    print("Dependances manquantes. Installer avec:")
    print("  pip install PyJWT cryptography")
    sys.exit(1)

KEYS_DIR = Path(__file__).parent / "licence-keys"
PRIVATE_KEY_FILE = KEYS_DIR / "licence-private.pem"
PUBLIC_KEY_FILE = KEYS_DIR / "licence-public.pem"

# Copie de la cle publique dans les resources du serveur
SERVER_PUBLIC_KEY = Path(__file__).parent.parent / "src" / "main" / "resources" / "licence-public.pem"

ISSUER = "instantiot.io"


def generate_keys():
    """Genere une paire de cles RSA 2048 bits"""
    KEYS_DIR.mkdir(parents=True, exist_ok=True)

    if PRIVATE_KEY_FILE.exists():
        print(f"  [WARN] La cle privee existe deja : {PRIVATE_KEY_FILE}")
        response = input("  Ecraser ? (y/N) : ").strip().lower()
        if response != "y":
            print("  Annule.")
            return

    # Generer la paire de cles
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_key = private_key.public_key()

    # Sauvegarder la cle privee (GARDER SECRET)
    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )
    PRIVATE_KEY_FILE.write_bytes(private_pem)
    os.chmod(PRIVATE_KEY_FILE, 0o600)

    # Sauvegarder la cle publique
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
    PUBLIC_KEY_FILE.write_bytes(public_pem)

    # Copier la cle publique dans les resources du serveur
    SERVER_PUBLIC_KEY.parent.mkdir(parents=True, exist_ok=True)
    SERVER_PUBLIC_KEY.write_bytes(public_pem)

    print("  [OK] Paire de cles RSA 2048 generee :")
    print(f"       Cle privee : {PRIVATE_KEY_FILE} (GARDER SECRET)")
    print(f"       Cle publique : {PUBLIC_KEY_FILE}")
    print(f"       Copie serveur : {SERVER_PUBLIC_KEY}")


def generate_licence(expires: str = None, licence_id: str = None):
    """Genere un JWT licence signe avec la cle privee.

    expires = None → licence lifetime (claim `exp` omis du JWT)
    expires = "YYYY-MM-DD" → licence avec expiration (trial, beta...)
    """

    if not PRIVATE_KEY_FILE.exists():
        print("  [ERREUR] Cle privee introuvable. Lancer d'abord :")
        print("    python3 generate-licence.py --generate-keys")
        sys.exit(1)

    # Charger la cle privee
    private_key = serialization.load_pem_private_key(
        PRIVATE_KEY_FILE.read_bytes(),
        password=None
    )

    # Generer l'ID licence
    if licence_id is None:
        uid = uuid.uuid4().hex[:12].upper()
        licence_id = f"INST-{uid[:4]}-{uid[4:8]}-{uid[8:12]}"

    # Construire le JWT — V1 : pas de claim `plan` (ignore par le serveur),
    # toutes les licences sont equivalentes (pas de feature gates).
    payload = {
        "sub": licence_id,
        "iss": ISSUER,
        "iat": int(datetime.now(timezone.utc).timestamp())
    }

    expires_str = "lifetime"
    if expires is not None:
        try:
            exp_date = datetime.strptime(expires, "%Y-%m-%d").replace(tzinfo=timezone.utc)
        except ValueError:
            print(f"  [ERREUR] Format de date invalide : {expires} (attendu: YYYY-MM-DD)")
            sys.exit(1)
        payload["exp"] = int(exp_date.timestamp())
        expires_str = expires

    token = jwt.encode(payload, private_key, algorithm="RS256")

    print()
    print("  ================================================")
    print(f"  Licence generee avec succes")
    print(f"  ================================================")
    print(f"  ID       : {licence_id}")
    print(f"  Expire   : {expires_str}")
    print(f"  Issuer   : {ISSUER}")
    print(f"  ================================================")
    print()
    print("  CLE DE LICENCE (a donner au client) :")
    print()
    print(f"  {token}")
    print()
    print("  Default admin password (a communiquer dans l'email d'achat) :")
    print(f"  {licence_id}")
    print()
    print("  ================================================")

    return token


def main():
    parser = argparse.ArgumentParser(
        description="InstantIoT Licence Generator",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
Examples:
  python3 generate-licence.py --generate-keys
  python3 generate-licence.py                          # lifetime
  python3 generate-licence.py --expires 2027-01-01     # avec exp
  python3 generate-licence.py --id INST-CLIENT-001     # ID custom
"""
    )
    parser.add_argument("--generate-keys", action="store_true",
                        help="Generer la paire de cles RSA (1ere fois)")
    parser.add_argument("--expires", type=str,
                        help="Date d'expiration YYYY-MM-DD (default: lifetime, claim `exp` omis)")
    parser.add_argument("--id", type=str,
                        help="ID licence custom (default: auto-genere INST-XXXX-XXXX-XXXX)")
    parser.add_argument("--plan", type=str,
                        help="[deprecated, ignore par le serveur V1] Garde pour compat")

    args = parser.parse_args()

    print()
    print("  InstantIoT — Licence Generator")
    print("  ================================")

    if args.generate_keys:
        generate_keys()
        return

    if args.plan:
        print("  [WARN] --plan est ignore par le serveur V1 — toutes les licences")
        print("         sont equivalentes (pas de feature gates).")

    generate_licence(expires=args.expires, licence_id=args.id)


if __name__ == "__main__":
    main()
