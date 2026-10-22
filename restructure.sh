#!/usr/bin/env bash
# restructure.sh
# Rebuilds the real Android project folder layout from the flat files
# that sit in the repository root (so you can upload everything from a phone
# without creating folders). Run by the GitHub Action before the build.
#
# Final layout:
#   settings.gradle, build.gradle, gradle.properties        (stay in the root)
#   src/main/AndroidManifest.xml
#   src/main/java/net/timelegend/chaka/viewer/*.java
#   src/main/res/values/{strings,themes}.xml
#   src/main/res/drawable/ic_*.xml

set -e
cd "$(dirname "$0")"

MAIN=src/main
JAVA=$MAIN/java/net/timelegend/chaka/viewer
RES=$MAIN/res

# put <file> <destination folder>
put() {
  if [ -f "$1" ]; then
    mkdir -p "$2"
    mv "$1" "$2/"
    echo "moved  $1 -> $2/"
  elif [ -f "$2/$1" ]; then
    echo "exists $2/$1"
  else
    echo "MISSING $1"; MISSING=1
  fi
}
MISSING=0

# manifest
put AndroidManifest.xml $MAIN

# java sources
for f in \
  CancellableAsyncTask CancellableTaskDefinition ContentInputStream \
  DocumentActivity MuPDFCancellableTaskDefinition MuPDFCore PageAdapter \
  PageView ReaderView RecentFiles Stepper TextActionModeCallback Tool
do
  put "$f.java" $JAVA
done

# values
put strings.xml $RES/values
put themes.xml  $RES/values

# drawables
put ic_chaka.xml          $RES/drawable
put ic_error_red_24dp.xml $RES/drawable

if [ "$MISSING" = "1" ]; then
  echo "Some files are missing - upload them to the repository root and try again."
  exit 1
fi
echo "Project structure ready."
