#!/bin/bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
swift build --package-path "$project_dir" --product KomadoHost
binary_dir="$(swift build --package-path "$project_dir" --show-bin-path)"
app_dir="$project_dir/.build/komado.app"
mkdir -p "$app_dir/Contents/MacOS"
cp "$project_dir/app/Info.plist" "$app_dir/Contents/Info.plist"
cp "$binary_dir/KomadoHost" "$app_dir/Contents/MacOS/KomadoHost"
codesign --force --sign - "$app_dir"
printf 'Built %s\n' "$app_dir"
