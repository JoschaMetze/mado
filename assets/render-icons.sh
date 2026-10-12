#!/bin/sh
# Regenerate every icon from assets/icon.svg. macOS only (uses AppKit to draw the SVG).
# Run it after changing the SVG, and commit what it writes.
set -e
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
swiftc -O -o "$tmp/render" assets/render.swift

# The same drawing without the margin macOS wants, for Android and the hub's web page.
sed 's/viewBox="0 0 1024 1024" width="1024" height="1024"/viewBox="100 100 824 824" width="824" height="824"/' \
    assets/icon.svg > hub/src/icon.svg

mac=mac/Mado/Assets.xcassets/AppIcon.appiconset
for px in 16 32 64 128 256 512 1024; do
    "$tmp/render" assets/icon.svg "$mac/icon-$px.png" "$px"
done

for spec in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    dir=frame-app/app/src/main/res/mipmap-${spec%%:*}
    mkdir -p "$dir"
    "$tmp/render" hub/src/icon.svg "$dir/ic_launcher.png" "${spec##*:}"
done

"$tmp/render" assets/icon.svg docs/images/icon.png 256
echo "Icons written."
