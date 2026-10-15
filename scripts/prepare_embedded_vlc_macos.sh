#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_DIR="$PROJECT_ROOT/composeApp/package-resources/vlc"

echo "==> Preparing embedded LibVLC for macOS..."

mkdir -p "$TARGET_DIR"

SOURCE_APP=""

if [ -d "/Applications/VLC.app" ]; then
    echo "Found system /Applications/VLC.app"
    SOURCE_APP="/Applications/VLC.app"
elif [ -d "$HOME/Applications/VLC.app" ]; then
    echo "Found user ~/Applications/VLC.app"
    SOURCE_APP="$HOME/Applications/VLC.app"
fi

MOUNT_DIR=""

cleanup() {
    if [ -n "$MOUNT_DIR" ] && [ -d "$MOUNT_DIR" ]; then
        echo "Detaching $MOUNT_DIR..."
        hdiutil detach "$MOUNT_DIR" -quiet || true
        rm -rf "$MOUNT_DIR" || true
    fi
}
trap cleanup EXIT

if [ -z "$SOURCE_APP" ]; then
    ARCH="$(uname -m)"
    echo "No local VLC.app found. Target architecture: $ARCH"

    # Try brew first if available
    if command -v brew >/dev/null 2>&1; then
        echo "Attempting to install VLC via Homebrew Cask..."
        if brew install --cask vlc; then
            if [ -d "/Applications/VLC.app" ]; then
                SOURCE_APP="/Applications/VLC.app"
            fi
        fi
    fi

    # Fallback to direct DMG download from VideoLAN
    if [ -z "$SOURCE_APP" ]; then
        VLC_VERSION="3.0.21"
        if [ "$ARCH" = "arm64" ]; then
            DMG_URL="https://get.videolan.org/vlc/${VLC_VERSION}/macosx/vlc-${VLC_VERSION}-arm64.dmg"
        else
            DMG_URL="https://get.videolan.org/vlc/${VLC_VERSION}/macosx/vlc-${VLC_VERSION}-intel64.dmg"
        fi

        TMP_DMG="/tmp/vlc-${ARCH}.dmg"
        echo "Downloading VLC DMG from $DMG_URL..."
        curl -fSL "$DMG_URL" -o "$TMP_DMG"

        MOUNT_DIR="$(mktemp -d /tmp/vlc_mount_XXXXXX)"
        echo "Mounting DMG to $MOUNT_DIR..."
        hdiutil attach "$TMP_DMG" -mountpoint "$MOUNT_DIR" -nobrowse -quiet
        rm -f "$TMP_DMG"

        SOURCE_APP="$MOUNT_DIR/VLC.app"
    fi
fi

if [ ! -d "$SOURCE_APP/Contents/MacOS/lib" ]; then
    echo "Error: Could not locate VLC libraries in $SOURCE_APP" >&2
    exit 1
fi

echo "Copying VLC libraries and plugins to $TARGET_DIR..."
rm -rf "$TARGET_DIR/lib" "$TARGET_DIR/plugins"
cp -R "$SOURCE_APP/Contents/MacOS/lib" "$TARGET_DIR/"
cp -R "$SOURCE_APP/Contents/MacOS/plugins" "$TARGET_DIR/"

echo "Embedded LibVLC layout:"
ls -lh "$TARGET_DIR/lib"

echo "==> Embedded LibVLC prepared successfully in $TARGET_DIR"
