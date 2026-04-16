#!/bin/bash
# ============================================================
# InstantIoT Server — Build native installer
#
# Detecte l'OS et genere l'installeur natif via jpackage.
# JRE embarque — le client n'a pas besoin d'installer Java.
#
# Usage:
#   ./packaging/build-installer.sh          # .dmg sur macOS, .deb sur Linux, .msi sur Windows
#   ./packaging/build-installer.sh app      # .app seulement (macOS, pour tester)
# ============================================================

set -e

APP_NAME="InstantIoT-Server"
APP_VERSION="1.0.0"
MAIN_CLASS="com.jeanloickdt.ApplicationKt"
VENDOR="InstantIoT"
DESCRIPTION="InstantIoT IoT Server — Real-time device relay"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_DIR/build/installer"

echo ""
echo "  ================================================"
echo "  InstantIoT Server — Build Installer"
echo "  ================================================"
echo ""

# ── Verifier jpackage ──────────────────────────────────────
if ! command -v jpackage &> /dev/null; then
    echo "  [ERREUR] jpackage introuvable."
    echo "  Installer Java 14+ (JDK, pas JRE)."
    exit 1
fi

echo "  jpackage: $(jpackage --version)"
echo "  Project:  $PROJECT_DIR"
echo ""

# ── Build le shadow JAR ───────────────────────────────────
echo "  [1/3] Building shadow JAR..."
cd "$PROJECT_DIR"
./gradlew shadowJar --quiet

JAR_FILE="$PROJECT_DIR/build/libs/instantiot-server-all.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "  [ERREUR] JAR introuvable: $JAR_FILE"
    exit 1
fi

JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
echo "  [OK] JAR: $JAR_FILE ($JAR_SIZE)"
echo ""

# ── Preparer le dossier input (jpackage veut un dossier) ───
INPUT_DIR="$PROJECT_DIR/build/jpackage-input"
rm -rf "$INPUT_DIR"
mkdir -p "$INPUT_DIR"
cp "$JAR_FILE" "$INPUT_DIR/"

# ── Detecter l'OS ─────────────────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"
TYPE_OVERRIDE="${1:-}"

echo "  [2/3] Detecting platform..."
echo "  OS:   $OS"
echo "  Arch: $ARCH"
echo ""

# ── Options communes ──────────────────────────────────────
COMMON_OPTS=(
    --name "$APP_NAME"
    --input "$INPUT_DIR"
    --main-jar "instantiot-server-all.jar"
    --main-class "$MAIN_CLASS"
    --app-version "$APP_VERSION"
    --vendor "$VENDOR"
    --description "$DESCRIPTION"
    --dest "$OUTPUT_DIR"
    --java-options "-Xms64m"
    --java-options "-Xmx512m"
    --java-options "-Dfile.encoding=UTF-8"
)

# ── Cleanup output ────────────────────────────────────────
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

# ── Build par plateforme ──────────────────────────────────
echo "  [3/3] Building installer..."
echo ""

case "$OS" in
    Darwin)
        if [ "$TYPE_OVERRIDE" = "app" ]; then
            PKG_TYPE="app-image"
            echo "  Target: macOS app-image (.app)"
        else
            PKG_TYPE="dmg"
            echo "  Target: macOS DMG installer"
        fi

        ICON_FILE="$SCRIPT_DIR/icon.icns"
        MAC_OPTS=()
        if [ -f "$ICON_FILE" ]; then
            MAC_OPTS+=(--icon "$ICON_FILE")
        fi

        jpackage \
            "${COMMON_OPTS[@]}" \
            "${MAC_OPTS[@]}" \
            --type "$PKG_TYPE" \
            --mac-package-name "$APP_NAME"
        ;;

    Linux)
        # Detecter le package manager
        if command -v dpkg &> /dev/null; then
            PKG_TYPE="deb"
            echo "  Target: Linux DEB package"
        elif command -v rpm &> /dev/null; then
            PKG_TYPE="rpm"
            echo "  Target: Linux RPM package"
        else
            PKG_TYPE="app-image"
            echo "  Target: Linux app-image"
        fi

        ICON_FILE="$SCRIPT_DIR/icon.png"
        LINUX_OPTS=()
        if [ -f "$ICON_FILE" ]; then
            LINUX_OPTS+=(--icon "$ICON_FILE")
        fi

        jpackage \
            "${COMMON_OPTS[@]}" \
            "${LINUX_OPTS[@]}" \
            --type "$PKG_TYPE" \
            --linux-shortcut \
            --linux-app-category "Network"
        ;;

    MINGW*|MSYS*|CYGWIN*)
        echo "  Target: Windows MSI installer"

        ICON_FILE="$SCRIPT_DIR/icon.ico"
        WIN_OPTS=()
        if [ -f "$ICON_FILE" ]; then
            WIN_OPTS+=(--icon "$ICON_FILE")
        fi

        jpackage \
            "${COMMON_OPTS[@]}" \
            "${WIN_OPTS[@]}" \
            --type msi \
            --win-menu \
            --win-shortcut \
            --win-menu-group "InstantIoT"
        ;;

    *)
        echo "  [ERREUR] OS non supporte: $OS"
        exit 1
        ;;
esac

echo ""
echo "  ================================================"
echo "  BUILD COMPLETE"
echo "  ================================================"
echo ""
echo "  Output: $OUTPUT_DIR/"
ls -lh "$OUTPUT_DIR/"
echo ""
