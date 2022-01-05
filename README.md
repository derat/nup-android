# nup-android

[![Build Status](https://storage.googleapis.com/derat-build-badges/bb278d31-46f9-4f06-8065-215d47c0878f.svg)](https://storage.googleapis.com/derat-build-badges/bb278d31-46f9-4f06-8065-215d47c0878f.html)

Android client for streaming music from [nup].

[nup]: http://github.com/derat/nup

## Overview

**Phone**
<p float="left">
  <img src="https://user-images.githubusercontent.com/40385/142279227-ec2f79b4-829b-4bbe-99ef-c2f72fdc5670.jpg"
       width="23.5%" alt="light mode screenshot">
  <img src="https://user-images.githubusercontent.com/40385/142279268-0f2cb56e-9897-48e0-a4ab-5f385509bc0b.jpg"
       width="23.5%" alt="dark mode screenshot">
  <img src="https://user-images.githubusercontent.com/40385/142279250-ceeeb873-7c4b-4a4b-8c6a-57b48c0008a3.jpg"
       width="23.5%" alt="browse screenshot">
  <img src="https://user-images.githubusercontent.com/40385/142279281-be37d66d-ef63-4b33-911f-58d032dbc05a.jpg"
       width="23.5%" alt="search screenshot">
</p>

**Android Auto**
<p float="left">
  <img src="https://user-images.githubusercontent.com/40385/148253193-585ee203-0fef-4858-b125-67f9f852c426.png"
       width="48%" alt="Android Auto playback screenshot">
  <img src="https://user-images.githubusercontent.com/40385/148253213-3a166711-273a-4268-b362-b54321bec12d.png"
       width="48%" alt="Android Auto presets screenshot">
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

### Android Auto

[Android Auto] support can be tested using the [Desktop Head Unit]. Once the DHU
is installed, select `Start head unit server` in the Android Auto app and run
the following from the `extras/google/auto` directory in the SDK:

```sh
adb forward tcp:5277 tcp:5277
./desktop-head-unit
```

[Android Auto]: https://www.android.com/auto/
[Desktop Head Unit]: https://developer.android.com/training/cars/testing#test-auto

## Testing

Run unit tests:

```sh
gradle test
```
