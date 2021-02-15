# nup-android

Android client for streaming music from [nup].

[nup]: http://github.com/derat/nup

## Compiling

```
$ gradle assembleDebug
```

## Installing

```
$ adb install -r /path/to/nup-android/nup/build/outputs/apk/debug/nup-debug.apk
```

## Debugging

Watch logs:

```
$ adb logcat --pid=$(adb shell pidof -s org.erat.nup)
```

Copy database off device:

```
$ adb shell "run-as org.erat.nup cat databases/NupSongs" >NupSongs
```

(Sigh: `sqlite3` isn't installed on production devices, the normal `adb shell`
user doesn't have permission to read the `org.erat.nup` data, and the
`org.erat.nup` user doesn't have permission to write to `/sdcard`.)

## Testing

Run unit tests:

```
$ gradle test
```
