#!/bin/sh
rm -rf bin/res bin/classes/org
ant debug && adb -d install -r bin/nup-debug.apk
