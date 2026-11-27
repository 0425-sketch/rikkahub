set -euo pipefail
run=$(cat tools/qa-device/stage-run)
sha=$(gh api "repos/$GH_REPO/actions/runs/$run" --jq .head_sha)
tag="qa-personal-$run-1"
for i in {1..100}; do
  if gh api "repos/$GH_REPO/releases/tags/$tag" | jq -e --arg sha "$sha" '.draft and .target_commitish == $sha and any(.assets[]; .name=="build-provenance.json")' > /dev/null; then break; fi
  gh api "repos/$GH_REPO/actions/runs/$run" --jq '.status + "-" + (.conclusion // "")' | grep -vE '^completed-(failure|cancelled)$'
  sleep 15
done
mkdir -p "$RUNNER_TEMP/candidate"
cd "$RUNNER_TEMP/candidate"
gh release download "$tag" -p build-provenance.json -p primary.apk
hash=$(jq -er --arg sha "$sha" 'select(.source_commit==$sha and .version_code==210 and .application_id=="me.rerere.rikkahub.personal") | .apk_sha256' build-provenance.json)
echo "$hash  primary.apk" | sha256sum -c -
signer=$(find "$ANDROID_HOME"/build-tools -name apksigner | sort -V | tail -1)
cert=$("$signer" verify --print-certs primary.apk | awk '/certificate SHA-256 digest:/ {print $NF}')
test "$cert" = '882c1cc87ccf36c2ac5eb178b8d8ef13ff74a3ac8b759b41aed6e48bddb4e1fd'
adb install -r primary.apk
adb shell dumpsys package me.rerere.rikkahub.personal | grep 'versionCode=210 '
mkdir -p "$RUNNER_TEMP/device-results"
echo "Build $sha APK $hash signer $cert" | tee "$RUNNER_TEMP/device-results/candidate.txt"
