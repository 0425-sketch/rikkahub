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
  curl -fsSL "https://api.github.com/repos/0425-sketch/rikkahub/releases/latest" -o "$RQA_DATA/latest.json"
  jq -e '.name=="RikkaHub Release 209（Release 1）" and .draft==false and .prerelease==false' "$RQA_DATA/latest.json"
  for name in build-provenance.json apk; do
    url=$(jq -r --arg name "$name" '.assets[]|select((.name==$name) or ($name=="apk" and (.name|endswith("-arm64.apk"))))|.browser_download_url' "$RQA_DATA/latest.json")
    dest=$name
    if [[ $name == apk ]]; then dest=primary.apk; fi
    curl -fsSL --retry 3 "$url" -o "$RQA_DATA/stage/$dest"
  done
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
