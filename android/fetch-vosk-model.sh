#!/usr/bin/env bash
# Download the Vosk small en-US model into android assets and write the
# `uuid` marker file vosk-android's StorageService.unpack expects.
#
# Run once after cloning. The model is ~50 MB compressed, ~68 MB unpacked,
# and is gitignored — the app won't build without it.

set -euo pipefail

VERSION="vosk-model-small-en-us-0.15"
URL="https://alphacephei.com/vosk/models/${VERSION}.zip"

cd "$(dirname "$0")/app/src/main/assets"

if [[ -d vosk-model ]]; then
    echo "vosk-model/ already present — delete it first if you want a fresh download."
    exit 0
fi

echo "Downloading ${VERSION}..."
curl -L -o "${VERSION}.zip" "$URL"
unzip -q "${VERSION}.zip"
mv "${VERSION}" vosk-model
rm "${VERSION}.zip"

# vosk-android's StorageService.unpack() expects a `uuid` file in the asset
# dir to track whether the model has been extracted to filesDir yet.
uuidgen > vosk-model/uuid

echo "Installed to $(pwd)/vosk-model ($(du -sh vosk-model | cut -f1))"
