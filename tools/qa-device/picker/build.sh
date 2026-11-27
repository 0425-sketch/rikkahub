# Disposable, network-free alternate document provider for the Android system's
# OpenDocument contract. No permission to read target app data or access network.
set -eu
src=$(realpath "$(dirname "$0")")
bt=$(ls -d "$ANDROID_HOME"/build-tools/[0-9]* | sort -V | tail -1)
sdk=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
out="$RUNNER_TEMP/fixture-picker-build"
mkdir -p "$out/classes" "$out/dex"
javac -source 8 -target 8 -classpath "$sdk" -d "$out/classes" "$src"/*.java
jar cf "$out/classes.jar" -C "$out/classes" .
"$bt/d8" --min-api 26 --lib "$sdk" --output "$out/dex" "$out/classes.jar"
"$bt/aapt2" link --manifest "$src/AndroidManifest.xml" -I "$sdk" -o "$out/raw.apk"
(cd "$out/dex" && zip -q "$out/raw.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/raw.apk" "$out/aligned.apk"
# This key signs only the disposable helper package org.rikkahub.qa.document.
keytool -genkeypair -keystore "$out/fixture.jks" -storepass disposable -keypass disposable -keyalg RSA -keysize 2048 -alias fixture -dname 'CN=Ephemeral emulator fixture only' -validity 1
"$bt/apksigner" sign --ks "$out/fixture.jks" --ks-pass pass:disposable --out "$out/helper.apk" "$out/aligned.apk"
rm "$out/fixture.jks"
adb install "$out/helper.apk"
