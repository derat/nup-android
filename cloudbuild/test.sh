#!/bin/bash -e

echo $(date -u) "Installing build dependencies..."
. cloudbuild/install_deps.sh
PATH="${PATH}:${GRADLE_HOME}/bin"

echo $(date -u) "Running unit tests..."
gradle testDebugUnitTest

echo $(date -u) "Building debug APK..."
gradle assembleDebug
