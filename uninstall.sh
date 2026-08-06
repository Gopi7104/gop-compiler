#!/usr/bin/env bash
# Removes a GopiLang installation created by install.sh: the install
# directory (~/gopilang by default) and its PATH symlink.
#
# Usage:
#   ./uninstall.sh
#   GOPILANG_INSTALL_DIR=/opt/gopilang ./uninstall.sh
set -euo pipefail

INSTALL_DIR="${GOPILANG_INSTALL_DIR:-$HOME/gopilang}"
LINK_DIR="${GOPILANG_LINK_DIR:-$HOME/.local/bin}"
LINK_PATH="$LINK_DIR/gopic"

echo "== GopiLang uninstaller =="
echo "Install dir:  $INSTALL_DIR"
echo "PATH symlink: $LINK_PATH"
echo

# Only remove the symlink if it actually points into this installation —
# never blindly delete whatever "gopic" happens to be on the user's PATH.
if [ -L "$LINK_PATH" ]; then
    TARGET="$(readlink "$LINK_PATH")"
    if [ "$TARGET" = "$INSTALL_DIR/bin/gopic" ]; then
        rm -f "$LINK_PATH"
        echo "Removed symlink: $LINK_PATH"
    else
        echo "Skipped $LINK_PATH — it points elsewhere ($TARGET), not at $INSTALL_DIR"
    fi
else
    echo "No symlink found at $LINK_PATH"
fi

if [ -d "$INSTALL_DIR" ]; then
    rm -rf "$INSTALL_DIR"
    echo "Removed install directory: $INSTALL_DIR"
else
    echo "No install directory found at $INSTALL_DIR"
fi

echo
echo "== GopiLang uninstalled successfully =="
