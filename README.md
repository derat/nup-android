# nup-android

[![Build Status](https://storage.googleapis.com/derat-build-badges/bb278d31-46f9-4f06-8065-215d47c0878f.svg)](https://storage.googleapis.com/derat-build-badges/bb278d31-46f9-4f06-8065-215d47c0878f.html)

Android client for streaming music from [nup].

[nup]: http://github.com/derat/nup

## Overview

<p float="left">
  <img src="https://user-images.githubusercontent.com/40385/142279227-ec2f79b4-829b-4bbe-99ef-c2f72fdc5670.jpg"
       width="24%" alt="light mode screenshot">
  <img src="https://user-images.githubusercontent.com/40385/142279268-0f2cb56e-9897-48e0-a4ab-5f385509bc0b.jpg"
       width="24%" alt="dark mode screenshot">
  <img src="https://user-images.githubusercontent.com/40385/142279250-ceeeb873-7c4b-4a4b-8c6a-57b48c0008a3.jpg"
       width="24%" alt="browse screenshot">
  <img src="https://user-images.githubusercontent.com/40385/142279281-be37d66d-ef63-4b33-911f-58d032dbc05a.jpg"
       width="24%" alt="search screenshot">
</p>

## Compiling

```sh
gradle assembleDebug
```

## Installing

```sh
adb install -r /path/to/nup-android/nup/build/outputs/apk/debug/nup-debug.apk
```

## Debugging

Watch logs:

```sh
adb logcat --pid=$(adb shell pidof -s org.erat.nup)
```

Copy database off device:

```sh
adb shell "run-as org.erat.nup cat databases/NupSongs" >NupSongs
```

(Sigh: `sqlite3` isn't installed on production devices, the normal `adb shell`
user doesn't have permission to read the `org.erat.nup` data, and the
`org.erat.nup` user doesn't have permission to write to `/sdcard`.)

## Testing

Run unit tests:

```sh
gradle test
```
