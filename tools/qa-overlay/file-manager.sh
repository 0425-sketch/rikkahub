set -euo pipefail
# Simulate the usual phone file manager with permission to install downloaded APKs.
# The Android 11 Google APIs AVD's DocumentsUI omits REQUEST_INSTALL_PACKAGES.
apk="$RQA_DATA/file-manager.apk"
curl -fsSL --retry 3 'https://f-droid.org/repo/me.zhanghai.android.files_40.apk' -o "$apk"
echo "2fe900bf43d725b655008d438f5ca46d0da0f301e3784403b5b96c3fc2df6e8a  $apk" | sha256sum -c -
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs "$apk" | grep -F 9c22a742fa200c85bafbc9924a02ddc8f3a0b975d4d7b5a8da21d7cf34ce2b20
adb install -g "$apk"
adb shell appops set --uid me.zhanghai.android.files MANAGE_EXTERNAL_STORAGE allow
adb shell appops set me.zhanghai.android.files REQUEST_INSTALL_PACKAGES allow
