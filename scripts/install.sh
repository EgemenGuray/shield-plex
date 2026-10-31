#!/usr/bin/env bash
# Installs (or updates) Plex HDR on a Shield over the network.
# Usage: ./scripts/install.sh <shield-ip> [path/to/PlexHDR-Shield.apk]
set -euo pipefail
ip="${1:?usage: $0 <shield-ip> [apk]}"
apk="${2:-PlexHDR-Shield.apk}"
adb connect "$ip:5555"
adb -s "$ip:5555" install -r "$apk"
adb -s "$ip:5555" shell monkey -p app.plexhdr.shield -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
echo "Installed and started Plex HDR on $ip"
