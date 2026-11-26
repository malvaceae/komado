#!/bin/bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
args=(--package-path "$project_dir" --only-use-versions-from-resolved-file)
if [[ $# -eq 1 && "$1" == "--universal" ]]; then
    args+=(--scratch-path "$project_dir/.build/universal" --arch arm64 --arch x86_64)
elif [[ $# -ne 0 ]]; then
    printf 'Usage: %s [--universal]\n' "$0" >&2
    exit 2
fi
swift build "${args[@]}" --product KomadoHost
binary_dir="$(swift build "${args[@]}" --show-bin-path)"
app_dir="$project_dir/.build/komado.app"
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Frameworks"
cp "$project_dir/app/Info.plist" "$app_dir/Contents/Info.plist"
cp "$binary_dir/KomadoHost" "$app_dir/Contents/MacOS/KomadoHost"
ditto "$binary_dir/WebRTC.framework" "$app_dir/Contents/Frameworks/WebRTC.framework"
codesign --force --sign - "$app_dir/Contents/Frameworks/WebRTC.framework"
codesign --force --sign - "$app_dir"
printf 'Built %s\n' "$app_dir"
