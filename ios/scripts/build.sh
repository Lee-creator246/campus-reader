#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v xcodegen >/dev/null || { echo 'Install XcodeGen first: brew install xcodegen'; exit 1; }
swift scripts/make_icon.swift CampusReader/Assets.xcassets/AppIcon.appiconset/AppIcon.png
xcodegen generate
xcodebuild -resolvePackageDependencies -project CampusReader.xcodeproj -scheme CampusReader
xcodebuild -project CampusReader.xcodeproj -scheme CampusReader -configuration Release -destination 'generic/platform=iOS' -derivedDataPath build/device CODE_SIGNING_ALLOWED=NO build
echo 'Unsigned device build only. Signing is required before installing on an iPhone.'
