#!/bin/bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
args=(--package-path "$project_dir" --only-use-versions-from-resolved-file)
configuration=debug
universal=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release) configuration=release ;;
        --universal) universal=true ;;
        *) printf 'Usage: %s [--release] [--universal]\n' "$0" >&2; exit 2 ;;
    esac
    shift
done
args+=(--configuration "$configuration")
if [[ "$universal" == true ]]; then
    args+=(--scratch-path "$project_dir/.build/universal" --arch arm64 --arch x86_64)
fi
swift build "${args[@]}" --product KomadoHost
binary_dir="$(swift build "${args[@]}" --show-bin-path)"
if [[ "$configuration" == release ]]; then
    app_dir="$project_dir/.build/komado-release.app"
else
    app_dir="$project_dir/.build/komado.app"
fi
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Frameworks"
cp "$project_dir/app/Info.plist" "$app_dir/Contents/Info.plist"
cp "$binary_dir/KomadoHost" "$app_dir/Contents/MacOS/KomadoHost"
ditto "$binary_dir/LiveKitWebRTC.framework" "$app_dir/Contents/Frameworks/LiveKitWebRTC.framework"
codesign --force --sign - "$app_dir/Contents/Frameworks/LiveKitWebRTC.framework"
codesign --force --sign - "$app_dir"
printf 'Built %s\n' "$app_dir"
