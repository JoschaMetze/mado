#!/bin/sh
# Notarize build/Mado.app so it opens on other Macs without a Gatekeeper warning.
#
# First sign with a Developer ID (see Signing.xcconfig) and run scripts/build.sh. Then use
# whichever of these holds your Apple credentials:
#
#   scripts/notarize.sh [profile]        Apple's notarytool, with a keychain profile made by
#                                        `xcrun notarytool store-credentials` (default name:
#                                        mado-notary)
#   scripts/notarize.sh asc [profile]    the App Store Connect CLI (https://asccli.sh), with
#                                        its default or a named authentication profile
#
# The result is build/Mado.zip, stapled, ready to attach to a release.
set -e
cd "$(dirname "$0")/.."
app=build/Mado.app
[ -d "$app" ] || { echo "Build first: scripts/build.sh" >&2; exit 1; }
if codesign -dv "$app" 2>&1 | grep -q "Signature=adhoc"; then
    echo "$app is ad-hoc signed. Notarization needs a Developer ID; see Signing.xcconfig." >&2
    exit 1
fi
ditto -c -k --keepParent "$app" build/Mado.zip
if [ "$1" = asc ]; then
    asc ${2:+--profile "$2"} notarization submit --file build/Mado.zip --wait --output table
else
    xcrun notarytool submit build/Mado.zip --keychain-profile "${1:-mado-notary}" --wait
fi
xcrun stapler staple "$app"
# Zip again so the download carries the stapled ticket.
ditto -c -k --keepParent "$app" build/Mado.zip
spctl --assess --type execute -v "$app"
echo "Notarized. Ship build/Mado.zip"
