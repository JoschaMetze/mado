#!/bin/sh
# Build Mado.app from the command line.
#   scripts/build.sh            build into mac/build/Mado.app
#   scripts/build.sh install    also copy it to /Applications and open it
#   scripts/build.sh test       run the unit tests
# Needs Xcode and XcodeGen (https://github.com/yonaskolb/XcodeGen).
set -e
cd "$(dirname "$0")/.."
xcodegen generate --quiet
if [ "$1" = test ]; then
    exec xcodebuild -project Mado.xcodeproj -scheme Mado -derivedDataPath build/DerivedData -quiet test
fi
xcodebuild -project Mado.xcodeproj -scheme Mado -configuration Release -derivedDataPath build/DerivedData -quiet build
rm -rf build/Mado.app
cp -R build/DerivedData/Build/Products/Release/Mado.app build/Mado.app
echo "Built $PWD/build/Mado.app"
codesign -dv build/Mado.app 2>&1 | grep -E "^(Authority|Signature|TeamIdentifier)" | head -3
if [ "$1" = install ]; then
    pkill -x Mado 2>/dev/null || true
    rm -rf /Applications/Mado.app
    cp -R build/Mado.app /Applications/Mado.app
    open /Applications/Mado.app
    echo "Installed to /Applications/Mado.app and opened"
fi
