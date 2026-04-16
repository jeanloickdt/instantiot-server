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
        echo "  Target: macOS (.app + .dmg)"

        ICON_FILE="$SCRIPT_DIR/icon.icns"
        MAC_OPTS=()
        if [ -f "$ICON_FILE" ]; then
            MAC_OPTS+=(--icon "$ICON_FILE")
        fi

        # 1. Builder en app-image d'abord (pour pouvoir patcher)
        jpackage \
            "${COMMON_OPTS[@]}" \
            "${MAC_OPTS[@]}" \
            --type app-image \
            --mac-package-name "$APP_NAME"

        APP_BUNDLE="$OUTPUT_DIR/$APP_NAME.app"

        # 2. Patcher Info.plist — marquer comme background app
        #    Sans ca, macOS ferme l'app car elle n'a pas de fenetre GUI
        if [ -d "$APP_BUNDLE" ]; then
            /usr/libexec/PlistBuddy -c "Add :LSBackgroundOnly bool true" "$APP_BUNDLE/Contents/Info.plist" 2>/dev/null || \
            /usr/libexec/PlistBuddy -c "Set :LSBackgroundOnly true" "$APP_BUNDLE/Contents/Info.plist" 2>/dev/null
            echo "  [OK] Patched Info.plist (LSBackgroundOnly=true)"
        fi

        # 3. Creer un launcher .command (double-clic ouvre Terminal + lance le serveur)
        LAUNCHER="$OUTPUT_DIR/Start-InstantIoT-Server.command"
        cat > "$LAUNCHER" << 'LAUNCHER_SCRIPT'
#!/bin/bash
# InstantIoT Server — Launcher
# Double-clic pour demarrer le serveur

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_BINARY="$SCRIPT_DIR/InstantIoT-Server.app/Contents/MacOS/InstantIoT-Server"

if [ ! -f "$APP_BINARY" ]; then
    echo ""
    echo "  [ERREUR] InstantIoT-Server.app introuvable."
    echo "  Placez ce fichier dans le meme dossier que l'app."
    echo ""
    read -p "  Appuyez sur Entree pour fermer..."
    exit 1
fi

echo ""
echo "  ================================================"
echo "  InstantIoT Server"
echo "  ================================================"
echo ""
echo "  Le serveur demarre..."
echo "  Le navigateur va s'ouvrir automatiquement."
echo ""
echo "  Pour arreter : fermez cette fenetre ou Ctrl+C"
echo "  ================================================"
echo ""

"$APP_BINARY"
LAUNCHER_SCRIPT
        chmod +x "$LAUNCHER"
        echo "  [OK] Launcher created: $LAUNCHER"

        # 4. Creer le DMG avec l'app + le launcher
        if [ "$TYPE_OVERRIDE" != "app" ]; then
            DMG_STAGING="$PROJECT_DIR/build/dmg-staging"
            rm -rf "$DMG_STAGING"
            mkdir -p "$DMG_STAGING"
            cp -R "$APP_BUNDLE" "$DMG_STAGING/"
            cp "$LAUNCHER" "$DMG_STAGING/"

            DMG_FILE="$OUTPUT_DIR/$APP_NAME-$APP_VERSION.dmg"
            echo "  [INFO] Creating DMG..."
            hdiutil create -volname "$APP_NAME" \
                -srcfolder "$DMG_STAGING" \
                -ov -format UDZO \
                "$DMG_FILE" 2>/dev/null
            rm -rf "$DMG_STAGING"
            echo "  [OK] DMG created: $DMG_FILE"
        fi
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
