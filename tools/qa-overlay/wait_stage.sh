set -euo pipefail
if [[ "$(cat "$RQA_CODE/mode")" == public ]]; then
  mkdir -p "$RQA_DATA/stage"
  repo_url=https://github.com/0425-sketch/rikkahub
  expected="$repo_url/releases/tag/custom-3.3.0-personal-f7d267a21e9b-45947662246-1"
  seen=false
  for attempt in {1..90}; do
    location=$(curl --silent --show-error -I -o /dev/null -w '%{redirect_url}' --connect-timeout 10 --max-time 20 "$repo_url/releases/latest" || true)
    if [ "$location" = "$expected" ]; then seen=true; break; fi
    sleep 30
  done
  test "$seen" = true
  tag=${expected##*/}
  file_origin="$repo_url/releases/download/$tag"
  curl -fsSL --retry 3 "$file_origin/build-provenance.json" -o "$RQA_DATA/stage/build-provenance.json"
  curl -fsSL --retry 3 "$file_origin/rikkahub-3.3.0-personal.10-f7d267a21e9b-arm64.apk" -o "$RQA_DATA/stage/primary.apk"
  # This endpoint is also read by the unmodified 209 in the AVD. Respect the shared runner IP's
  # anonymous quota, as indicated by GitHub itself, before asking the device to query it.
  for attempt in {1..4}; do
    status=$(curl -sS -D "$RQA_DATA/api-headers" -o "$RQA_DATA/api-meta" -w '%{http_code}' 'https://api.github.com/repos/0425-sketch/rikkahub/releases?per_page=100&page=1')
    if [[ $status == 200 ]]; then
      jq -e --arg tag "$tag" 'any(.[]; .tag_name==$tag and .name=="RikkaHub Release 209（Release 1）" and .draft==false and .prerelease==false)' "$RQA_DATA/api-meta" && break
      sleep 10
      continue
    fi
    echo "Public API HTTP $status"
    jq -r '.message' "$RQA_DATA/api-meta"
    awk 'tolower($1)~/^x-ratelimit-(limit|remaining|reset):$|^retry-after:$/ {print}' "$RQA_DATA/api-headers" | tr -d '\r'
    seconds=$(python - "$RQA_DATA/api-headers" <<'READ'
import sys,time
headers=dict(line.strip().lower().split(': ',1) for line in open(sys.argv[1]) if ': ' in line)
print(max(15,int(headers.get('retry-after','0')),int(headers.get('x-ratelimit-reset','0'))-int(time.time())+5))
READ
)
    if [[ $status != 403 && $status != 429 ]]; then exit 1; fi
    sleep "$seconds"
  done
  test "$status" = 200
  hash=$(jq -er '.apk_sha256' "$RQA_DATA/stage/build-provenance.json")
  echo "$hash  $RQA_DATA/stage/primary.apk" | sha256sum -c -
  exit 0
fi
run=$(cat "$RQA_CODE/build-run")
tag="qa-personal-$run-1"
sha=$(gh api "repos/$GH_REPO/actions/runs/$run" --jq .head_sha)
for i in {1..120}; do
  if gh release view "$tag" --json isDraft,targetCommitish,assets | jq -e --arg sha "$sha" '.isDraft and .targetCommitish==$sha and (([.assets[].name] | index("build-provenance.json")) != null)' >/dev/null; then break; fi
  gh api "repos/$GH_REPO/actions/runs/$run" | jq -e '.status != "completed" or .conclusion=="success"' >/dev/null
  sleep 15
done
mkdir -p "$RQA_DATA/stage" "$RQA_DATA/next"
gh release download "$tag" -D "$RQA_DATA/stage" -p primary.apk -p next-candidate.apk -p build-provenance.json -p release-notes.txt
job=$(gh api "repos/$GH_REPO/actions/runs/$run/jobs" --jq '.jobs[]|select(.name=="Restore and build (primary)")|.id')
jq -e --arg sha "$sha" '.source_commit==$sha and .version_code==210' "$RQA_DATA/stage/build-provenance.json"
python "$RQA_CODE/successor.py" --proof "$RQA_DATA/stage/build-provenance.json" --apk "$RQA_DATA/stage/next-candidate.apk" --job "$job" --output "$RQA_DATA/next"
