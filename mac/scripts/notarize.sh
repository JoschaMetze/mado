#!/bin/sh
# Notarize build/Mado.app so it opens on other Macs without a Gatekeeper warning.
#
# Before the first run:
#   1. Sign with a Developer ID (see Signing.xcconfig) and run scripts/build.sh.
#   2. Store notary credentials in your keychain, once, under a profile name:
#        xcrun notarytool store-credentials mado-notary
#      (it asks for your Apple ID, team ID and an app-specific password).
#
# Then:  scripts/notarize.sh [profile-name]     (default profile: mado-notary)
set -e
cd "$(dirname "$0")/.."
profile="${1:-mado-notary}"
app=build/Mado.app
[ -d "$app" ] || { echo "Build first: scripts/build.sh" >&2; exit 1; }
if codesign -dv "$app" 2>&1 | grep -q "Signature=adhoc"; then
    echo "$app is ad-hoc signed. Notarization needs a Developer ID; see Signing.xcconfig." >&2
    exit 1
fi
ditto -c -k --keepParent "$app" build/Mado.zip
xcrun notarytool submit build/Mado.zip --keychain-profile "$profile" --wait
xcrun stapler staple "$app"
# Zip again so the download carries the stapled ticket.
ditto -c -k --keepParent "$app" build/Mado.zip
spctl --assess --type execute -v "$app"
echo "Notarized. Ship build/Mado.zip"
