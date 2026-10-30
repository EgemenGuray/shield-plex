#!/usr/bin/env bash
# Asks the Shield what HDR formats its display stack will actually output to the
# connected TV. Needs adb on your computer and "Network debugging" turned on in
# Shield Settings > Device Preferences > Developer options.
#
# Usage: ./probe-hdr10plus.sh <shield-ip>
#
# HDR type numbers Android reports: 1 = Dolby Vision, 2 = HDR10, 3 = HLG, 4 = HDR10+
# If 4 is missing while the QN900B is connected, no app can make the Shield send HDR10+.
set -u
ip="${1:?usage: $0 <shield-ip>}"
dev="$ip:5555"

adb connect "$dev" >/dev/null || exit 1
sh() { adb -s "$dev" shell "$@"; }

echo "== Firmware"
sh getprop ro.product.model
sh getprop ro.build.version.release
sh getprop ro.build.display.id

echo
echo "== HDR types the display reports (look for 4 = HDR10+)"
sh dumpsys display | grep -iE "hdr" | sort -u | head -20

echo
echo "== SurfaceFlinger / composer HDR info"
sh dumpsys SurfaceFlinger | grep -iE "hdr" | sort -u | head -20

echo
echo "== Any HDR10+ mention in the vendor codec list"
sh 'cat /vendor/etc/media_codecs*.xml /odm/etc/media_codecs*.xml 2>/dev/null' \
  | grep -iE "hdr10plus|hdr10\+" || echo "(none)"

echo
echo "== Audio passthrough formats the HDMI sink advertises"
sh dumpsys audio | grep -iE "encodings|surround|AC3|E_AC3|DTS|TRUEHD|MAT" | sort -u | head -20
