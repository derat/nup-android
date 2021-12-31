#!/bin/bash -e

# Saves and restores .gradle directories from Cloud Storage.

HASH=$(cat $(find -name build.gradle | sort) | sha1sum | cut -d " " -f 1)
ARCHIVE_URL="gs://${PROJECT_ID}-cache/nup-android_gradle-${HASH}.tgz"
ARCHIVE_FILE=/tmp/gradle-cache.tgz

function log {
  echo $(date -u) $*
}

case $1 in
  --load)
    if ! gsutil ls "$ARCHIVE_URL" >/dev/null 2>&1; then
      log "${ARCHIVE_URL} doesn't exist"
      exit 0
    fi
    log "Copying ${ARCHIVE_URL} to ${ARCHIVE_FILE}..."
    gsutil cp "$ARCHIVE_URL" "$ARCHIVE_FILE" || exit 0
    log "Decompressing ${ARCHIVE_FILE} to ${GRADLE_USER_HOME}..."
    mkdir -p "$GRADLE_USER_HOME"
    tar zxf "$ARCHIVE_FILE" -C "$GRADLE_USER_HOME" || exit 0
    log "Done restoring .gradle"
    ;;

  --save)
    if gsutil ls "$ARCHIVE_URL" >/dev/null 2>&1; then
      log "${ARCHIVE_URL} already exists"
      exit 0
    fi
    log "Compressing ${GRADLE_USER_HOME} to ${ARCHIVE_FILE}..."
    tar zcf "$ARCHIVE_FILE" --ignore-failed-read -C "$GRADLE_USER_HOME" \
      caches wrapper
    log "Copying ${ARCHIVE_FILE} to ${ARCHIVE_URL}..."
    gsutil cp "$ARCHIVE_FILE" "$ARCHIVE_URL"
    log "Done saving .gradle"
    ;;

  *)
    echo "--load or --save must be supplied" >&2
    exit 1
    ;;
esac
