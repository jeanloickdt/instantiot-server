#!/usr/bin/env sh
#
# InstantIoT Server — one-line installer for Linux (incl. Raspberry Pi)
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/jeanloickdt/instantiot-server/main/install.sh | sh
#
# What it does:
#   1. Detects the architecture (amd64 / arm64).
#   2. Fetches the latest GitHub release of jeanloickdt/instantiot-server.
#   3. Downloads the matching .deb installer.
#   4. Installs it with apt (so dependencies get pulled correctly).
#   5. Starts the systemd service.
#   6. Prints the admin panel URL.
#
# Requirements:
#   - Linux with apt (Debian, Ubuntu, Raspberry Pi OS 64-bit, etc.)
#   - sudo access
#   - curl
#
# License: AGPLv3 — https://github.com/jeanloickdt/instantiot-server
#

set -eu

REPO="jeanloickdt/instantiot-server"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# ───────────────────────────────────────────────────────────────
# Pretty output
# ───────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    GREEN='\033[0;32m'
    BLUE='\033[0;34m'
    YELLOW='\033[0;33m'
    RED='\033[0;31m'
    BOLD='\033[1m'
    RESET='\033[0m'
else
    GREEN=''; BLUE=''; YELLOW=''; RED=''; BOLD=''; RESET=''
fi

step()  { printf "${BLUE}==>${RESET} %s\n" "$1"; }
ok()    { printf "${GREEN}✓${RESET}   %s\n" "$1"; }
warn()  { printf "${YELLOW}!${RESET}   %s\n" "$1"; }
die()   { printf "${RED}✗${RESET}   %s\n" "$1" >&2; exit 1; }

# ───────────────────────────────────────────────────────────────
# 1. Sanity checks
# ───────────────────────────────────────────────────────────────
printf "${BOLD}InstantIoT Server installer${RESET}\n\n"

OS="$(uname -s)"
[ "$OS" = "Linux" ] || die "This installer only supports Linux. For macOS/Windows, download the installer from https://github.com/$REPO/releases."

command -v curl >/dev/null 2>&1 || die "curl is required. Install it with: sudo apt install -y curl"
command -v apt-get >/dev/null 2>&1 || die "apt is required (Debian, Ubuntu, Raspberry Pi OS, etc.)."

# Detect arch
RAW_ARCH="$(uname -m)"
case "$RAW_ARCH" in
    x86_64|amd64)   DEB_ARCH="amd64" ;;
    aarch64|arm64)  DEB_ARCH="arm64" ;;
    armv7l|armv6l)
        die "32-bit ARM ($RAW_ARCH) is not supported. Use a Raspberry Pi 4/5 with Raspberry Pi OS 64-bit."
        ;;
    *)
        die "Unsupported architecture: $RAW_ARCH"
        ;;
esac
ok "Detected architecture: $DEB_ARCH"

# ───────────────────────────────────────────────────────────────
# 2. Fetch latest release
# ───────────────────────────────────────────────────────────────
step "Fetching the latest InstantIoT Server release..."

# Use the GitHub releases/latest API. Parse JSON without jq (sed) so we
# don't add a dependency. Look for the .deb asset matching our arch.
API_URL="https://api.github.com/repos/$REPO/releases/latest"
RELEASE_JSON="$(curl -fsSL "$API_URL" 2>/dev/null || true)"

if [ -z "$RELEASE_JSON" ]; then
    die "Could not reach GitHub API. Check your internet connection and try again."
fi

# Extract the version tag for display
TAG="$(printf "%s" "$RELEASE_JSON" | sed -n 's/.*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
[ -n "$TAG" ] || die "Could not determine the latest version tag from the GitHub API."

# Extract the .deb download URL for our arch. The pattern matches:
#   "browser_download_url": "...something-amd64.deb"
# or "...something_amd64.deb" — we accept both separators.
DEB_URL="$(printf "%s" "$RELEASE_JSON" \
    | tr ',' '\n' \
    | grep -E '"browser_download_url"' \
    | grep -E "${DEB_ARCH}\.deb\"" \
    | sed -E 's/.*"browser_download_url":[[:space:]]*"([^"]+)".*/\1/' \
    | head -n1)"

if [ -z "$DEB_URL" ]; then
    die "No .deb installer found for arch '$DEB_ARCH' in release $TAG. Available assets: see https://github.com/$REPO/releases/tag/$TAG"
fi

ok "Latest version: $TAG"
ok "Downloading: $DEB_URL"

# ───────────────────────────────────────────────────────────────
# 3. Download
# ───────────────────────────────────────────────────────────────
DEB_FILE="$TMPDIR/instantiot-server.deb"
curl -fsSL --progress-bar -o "$DEB_FILE" "$DEB_URL" \
    || die "Download failed."
ok "Downloaded $(du -h "$DEB_FILE" | cut -f1) to $DEB_FILE"

# ───────────────────────────────────────────────────────────────
# 4. Install via apt (so dependencies get resolved automatically)
# ───────────────────────────────────────────────────────────────
step "Installing InstantIoT Server (may prompt for sudo password)..."

# `apt install ./local.deb` resolves dependencies; `dpkg -i` does not.
# We use sudo via env to keep this script POSIX-clean.
if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
else
    SUDO="sudo"
fi

# Refresh apt index quietly so any missing dep can be pulled.
$SUDO apt-get update -qq

if ! $SUDO apt-get install -y "$DEB_FILE" >/dev/null 2>&1; then
    # Fallback to dpkg + apt-get -f install (older systems)
    warn "apt install of .deb failed, falling back to dpkg..."
    $SUDO dpkg -i "$DEB_FILE" || true
    $SUDO apt-get -y -f install >/dev/null 2>&1 \
        || die "Installation failed. Check 'dpkg -i $DEB_FILE' manually."
fi
ok "Package installed"

# ───────────────────────────────────────────────────────────────
# 5. Start the systemd service
# ───────────────────────────────────────────────────────────────
step "Starting the systemd service..."

# Service name comes from the .deb post-install. Default convention:
#   instantiot-server.service
SERVICE="instantiot-server"

if $SUDO systemctl is-enabled "$SERVICE" >/dev/null 2>&1; then
    ok "Service '$SERVICE' is already enabled at boot"
else
    $SUDO systemctl enable "$SERVICE" >/dev/null 2>&1 \
        && ok "Service '$SERVICE' enabled at boot" \
        || warn "Could not enable '$SERVICE'. The .deb post-install script may have done it already."
fi

if $SUDO systemctl restart "$SERVICE" >/dev/null 2>&1; then
    ok "Service '$SERVICE' started"
else
    warn "Could not start '$SERVICE' automatically. Try: sudo systemctl status $SERVICE"
fi

# ───────────────────────────────────────────────────────────────
# 6. Print success banner
# ───────────────────────────────────────────────────────────────
# Try to find the LAN IP for a friendlier URL.
LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$LAN_IP" ] || LAN_IP="<server-ip>"

printf "\n"
printf "${GREEN}${BOLD}✓ InstantIoT Server $TAG installed and running.${RESET}\n\n"
printf "  Admin panel: ${BOLD}http://%s:8080${RESET}\n" "$LAN_IP"
printf "  Or from the server itself: ${BOLD}http://localhost:8080${RESET}\n\n"
printf "  ${BOLD}First login:${RESET} username ${BOLD}admin${RESET}, password ${BOLD}admin${RESET}\n"
printf "  You will be prompted to set a new password immediately.\n\n"
printf "  ${BOLD}Useful commands:${RESET}\n"
printf "    sudo systemctl status $SERVICE\n"
printf "    sudo systemctl restart $SERVICE\n"
printf "    sudo journalctl -u $SERVICE -f\n\n"
printf "  ${BOLD}Docs:${RESET} https://docs.instantiot.io/docs/overview-2\n"
printf "  ${BOLD}Source:${RESET} https://github.com/$REPO (AGPLv3)\n\n"
