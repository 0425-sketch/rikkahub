set -euo pipefail
mkdir "$RQA_DATA/origin"
cp "$RQA_CODE/fixture.py" "$RQA_DATA/origin/fixture.py"
cd "$RQA_DATA/origin"
python3 fixture.py --init-ca
hash=$(openssl x509 -in offline-fixture-ca.crt -subject_hash_old -noout)
# Android API 30 userdebug. Mount the fixture trust anchor only in the disposable AVD.
until adb shell 'test -d /system/etc/security/cacerts'; do sleep 1; done
adb shell mkdir -p /data/local/tmp/fixture-system-certs
adb shell 'cp -a /system/etc/security/cacerts/. /data/local/tmp/fixture-system-certs/'
adb push offline-fixture-ca.crt "/data/local/tmp/fixture-system-certs/$hash.0"
adb shell "chmod 644 /data/local/tmp/fixture-system-certs/$hash.0 && chown root:root /data/local/tmp/fixture-system-certs/$hash.0 && chcon u:object_r:system_file:s0 /data/local/tmp/fixture-system-certs/$hash.0"
# API 30 has the system Conscrypt trust store (no Android 14 APEX namespaces).
adb shell 'mount --bind /data/local/tmp/fixture-system-certs /system/etc/security/cacerts'
adb shell "test -r /proc/1/root/system/etc/security/cacerts/$hash.0"
adb shell 'am force-stop com.android.providers.downloads'
adb reverse tcp:18734 tcp:18734
