#!/bin/bash -e

# Installs the Android SDK to $ANDROID_HOME and Gradle to $GRADLE_HOME.

CMDLINE_TOOLS_VERSION=9123335
ANDROID_VERSION=33
BUILD_TOOLS_VERSION=33.0.1
GRADLE_VERSION=7.6

if [ -z "$ANDROID_HOME" ] || [ -z "$GRADLE_HOME" ]; then
  echo "ANDROID_HOME and GRADLE_HOME must be set" >&2
  exit 2
fi

echo "Installing dependencies to $ANDROID_HOME and $GRADLE_HOME"

# TODO: If I switch this to a Dockerfile, run apt-get upgrade too.
apt-get update
apt-get install -y --no-install-recommends \
  gnupg \
  openjdk-11-jdk:amd64 \
  openjdk-11-jdk-headless:amd64 \
  openjdk-11-jre:amd64 \
  openjdk-11-jre-headless:amd64 \
  unzip \
  wget

wget -q -O- https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key add -
echo 'deb [arch=amd64] https://packages.cloud.google.com/apt cloud-sdk main' \
    >>/etc/apt/sources.list.d/cloud-sdk.list
apt-get update
apt-get install -y --no-install-recommends google-cloud-sdk

wget -q -O /tmp/tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
mkdir -p "$ANDROID_HOME"
unzip /tmp/tools.zip -d "$ANDROID_HOME"

# The zip file seems to put its binaries in the wrong location:
# https://stackoverflow.com/q/65262340/6882947
yes | "${ANDROID_HOME}/cmdline-tools/bin/sdkmanager" \
  --sdk_root="${ANDROID_HOME}" --install \
  "build-tools;${BUILD_TOOLS_VERSION}" \
  "cmdline-tools;latest" \
  "extras;google;google_play_services" \
  "platform-tools" \
  "platforms;android-${ANDROID_VERSION}"
yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses

wget -q -O /tmp/gradle.zip \
  "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
parent="$(dirname "$GRADLE_HOME")"
mkdir -p "$parent"
unzip /tmp/gradle.zip -d "$parent"
ln -s "${parent}/gradle-${GRADLE_VERSION}" "$GRADLE_HOME"
