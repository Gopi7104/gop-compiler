#!/usr/bin/env bash
# Installs GopiLang into a stable, relocatable location (~/gopilang by
# default) so that "gopic" keeps working after "mvn clean" — unlike pointing
# straight at target/, which mvn clean deletes.
#
# Usage:
#   ./install.sh                  # installs to ~/gopilang
#   GOPILANG_INSTALL_DIR=/opt/gopilang ./install.sh   # installs elsewhere
set -euo pipefail

# Resolve this script's own real directory (following symlinks), so it works
# whether run as ./install.sh or via an absolute/relative path from elsewhere.
resolve_script_dir() {
    local source="${BASH_SOURCE[0]}"
    while [ -h "$source" ]; do
        local dir
        dir="$(cd -P "$(dirname "$source")" && pwd)"
        source="$(readlink "$source")"
        [[ "$source" != /* ]] && source="$dir/$source"
    done
    cd -P "$(dirname "$source")" && pwd
}

REPO_DIR="$(resolve_script_dir)"
INSTALL_DIR="${GOPILANG_INSTALL_DIR:-$HOME/gopilang}"
LINK_DIR="${GOPILANG_LINK_DIR:-$HOME/.local/bin}"

echo "== GopiLang installer =="
echo "Repository:    $REPO_DIR"
echo "Install to:    $INSTALL_DIR"
echo "PATH symlink:  $LINK_DIR/gopic"
echo

# --- 1. Verify Java ----------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
    echo "Error: no 'java' found on PATH. GopiLang requires Java 21 or later." >&2
    exit 1
fi

JAVA_VERSION_STRING="$(java -version 2>&1 | head -n 1)"
JAVA_VERSION_QUOTED="$(echo "$JAVA_VERSION_STRING" | sed -E 's/^[^"]*"([^"]+)".*$/\1/')"
JAVA_MAJOR="${JAVA_VERSION_QUOTED%%.*}"
JAVA_MAJOR="${JAVA_MAJOR%%_*}"
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "Error: GopiLang requires Java 21 or later, found: $JAVA_VERSION_STRING" >&2
    exit 1
fi
echo "Found Java $JAVA_MAJOR: OK"

# --- 2. Build the distribution ------------------------------------------
echo
echo "Building GopiLang (mvn package)..."
(cd "$REPO_DIR" && mvn -q package)

DIST_DIR="$REPO_DIR/target/gopilang"
if [ ! -d "$DIST_DIR" ]; then
    echo "Error: build did not produce $DIST_DIR — check the Maven output above." >&2
    exit 1
fi
echo "Build OK: $DIST_DIR"

# --- 3. Install, replacing any previous installation --------------------
echo
echo "Installing to $INSTALL_DIR..."
STAGING_DIR="$INSTALL_DIR.installing"
rm -rf "$STAGING_DIR"
cp -R "$DIST_DIR" "$STAGING_DIR"
rm -rf "$INSTALL_DIR"
mv "$STAGING_DIR" "$INSTALL_DIR"
echo "Installed: $INSTALL_DIR"

# --- 4. Create/update the PATH symlink -----------------------------------
mkdir -p "$LINK_DIR"
ln -sf "$INSTALL_DIR/bin/gopic" "$LINK_DIR/gopic"
echo "Linked:    $LINK_DIR/gopic -> $INSTALL_DIR/bin/gopic"

echo
echo "== GopiLang installed successfully =="
echo
echo "Installation layout:"
echo "  $INSTALL_DIR/bin/gopic       - launcher"
echo "  $INSTALL_DIR/lib/gopilang.jar"
echo "  $INSTALL_DIR/examples/"
echo "  $INSTALL_DIR/README.md"
echo

if [[ ":$PATH:" != *":$LINK_DIR:"* ]]; then
    echo "NOTE: $LINK_DIR is not on your PATH. Add this to your shell profile:"
    echo "  export PATH=\"$LINK_DIR:\$PATH\""
    echo
fi

echo "Try it:"
echo "  gopic $INSTALL_DIR/examples/hello.gopi"
