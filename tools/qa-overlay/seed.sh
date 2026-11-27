set -euo pipefail
tag=custom-3.3.0-personal-590dca0ef8ca-45763808599-1
apk=rikkahub-3.3.0-personal.9-590dca0ef8ca-arm64.apk
curl -fL --retry 3 "https://github.com/0425-sketch/rikkahub/releases/download/$tag/$apk" -o "$RQA_DATA/baseline.apk"
cd "$RQA_DATA"
echo 'de0657f2ee1d08e2ae9bac27ce8f36e67c7e9bf8ab3a4c57f2073d9e4f24af8b  baseline.apk' | sha256sum -c -
adb install baseline.apk
adb shell am start -W -n me.rerere.rikkahub.personal/me.rerere.rikkahub.RouteActivity
for i in {1..70}; do
 if adb shell 'run-as me.rerere.rikkahub.personal test -s files/datastore/settings.preferences_pb'; then break; fi
 sleep 2
done
cd "$RQA_CODE"
python -u provider.py > "$RQA_DATA/provider.log" 2>&1 &
python device.py prefs
for i in {1..80}; do
 if curl --noproxy '*' -m 2 -fsS http://127.0.0.1:18413/api/conversations -o "$RQA_DATA/empty.json"; then break; fi
 sleep 2
done
python device.py chat first
python records.py memory
python records.py capture 209
