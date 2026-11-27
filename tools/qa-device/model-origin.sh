set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu.avd_name | tr -d '\r')" = release_qa
adb root
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 0
# Restrict the offline origin to the production adapter's one fixed hostname.
# No requests are forwarded. All app settings and credentials are synthetic.
cd "$RUNNER_TEMP"
umask 077
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 \
  -subj '/CN=RikkaHub disposable fixture' -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
  -addext 'nameConstraints=critical,permitted;DNS:api.openai.com' \
  -keyout origin-ca.key -out origin-ca.pem >/dev/null 2>&1
openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj '/CN=api.openai.com' \
  -keyout origin-leaf.key -out origin-leaf.req >/dev/null 2>&1
echo 'subjectAltName=DNS:api.openai.com' > origin-ext
echo 'extendedKeyUsage=serverAuth' >> origin-ext
echo 'basicConstraints=critical,CA:FALSE' >> origin-ext
openssl x509 -req -in origin-leaf.req -CA origin-ca.pem -CAkey origin-ca.key -CAcreateserial \
  -days 1 -extfile origin-ext -out origin-leaf.pem >/dev/null 2>&1
rm origin-ca.key
hash=$(openssl x509 -in origin-ca.pem -subject_hash_old -noout)
folder=/data/misc/user/0/cacerts-added
adb shell mkdir -p "$folder"
adb push origin-ca.pem "$folder/$hash.0"
adb shell chown system:system "$folder" "$folder/$hash.0"
adb shell chmod 755 "$folder"
adb shell chmod 644 "$folder/$hash.0"
adb shell restorecon -RF "$folder"
adb reverse tcp:18415 tcp:18415
adb shell settings put global http_proxy 127.0.0.1:18415
