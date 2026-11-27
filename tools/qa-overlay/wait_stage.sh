set -euo pipefail
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
