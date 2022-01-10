#!/bin/bash -e

echo $(date -u) "Installing build dependencies..."
. cloudbuild/install_deps.sh
PATH="${PATH}:${GRADLE_HOME}/bin"

# Copy test results and the APK to Cloud Storage even if the build fails.
function copy_artifacts {
  local dest="gs://${PROJECT_ID}-artifacts/nup-android-test/${BUILD_ID}"
  echo "Copying artifacts to ${dest}"
  tar czf tests.tgz -C nup/build/reports tests || true
  gsutil cp tests.tgz "${dest}/tests.tgz" || true
  gsutil cp nup/build/outputs/apk/debug/nup-debug.apk \
    "${dest}/nup-debug.apk" || true

}
trap copy_artifacts EXIT

echo $(date -u) "Running unit tests..."
gradle testDebugUnitTest

echo $(date -u) "Building debug APK..."
gradle assembleDebug
