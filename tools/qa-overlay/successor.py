"""Prepare an in-memory channel's nonpublic successor from the actual second signed build."""
import os,argparse,hashlib,json,pathlib,re,subprocess
cli=argparse.ArgumentParser()
cli.add_argument('--proof',type=pathlib.Path,required=True,help='exact staged Release 1 proof')
cli.add_argument('--apk',type=pathlib.Path,required=True,help='exact nonpublic successor APK')
cli.add_argument('--job',type=int,required=True,help='Actual primary signed build Actions job ID; isolates an offline tag from the public run tag.')
cli.add_argument('--output',type=pathlib.Path,required=True)
a=cli.parse_args()
p=json.loads(a.proof.read_bytes());sdk=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/37.0.0'
manifest=subprocess.check_output([sdk/'aapt','dump','badging',a.apk],text=True)
identity=re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",manifest,re.M)
assert identity and "native-code: 'arm64-v8a'" in manifest.splitlines() and 'application-debuggable' not in manifest.splitlines()
app,code,name=identity.groups()
assert app==p['application_id'] and int(code)==p['version_code']+1
expected=re.fullmatch(r'(\d+\.\d+\.\d+)-personal\.(\d+)',p['version_name'])
assert name==f'{expected[1]}-personal.{int(expected[2])+1}'
sign=subprocess.check_output([sdk/'apksigner','verify','--print-certs',a.apk],text=True)
assert re.findall(r'certificate SHA-256 digest: ([a-fA-F0-9]+)',sign)==[p['certificate_sha256']]
match=re.fullmatch(r'custom-(\d+\.\d+\.\d+)-personal-([a-f0-9]{12})-(\d+)-(\d+)',p['tag'])
assert match and match[2]==p['source_commit'][:12] and str(a.job)!=match[3]
with a.apk.open('rb') as f: sha=hashlib.file_digest(f,'sha256').hexdigest()
out=dict(application_id=app,version_code=int(code),version_name=name,architectures=['arm64-v8a'],
  certificate_sha256=p['certificate_sha256'],apk_size=a.apk.stat().st_size,apk_sha256=sha,
  source_commit=p['source_commit'],tag=f'custom-{match[1]}-personal-{match[2]}-{a.job}-{match[4]}',
  actions_url=f'https://github.com/0425-sketch/rikkahub/actions/runs/{match[3]}/job/{a.job}',
  fixture_scope='Nonpublic emulator test. Local tag uses actual build job ID to distinguish both outputs of the same workflow run.')
a.output.mkdir(exist_ok=True)
(a.output/'build-provenance.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
(a.output/'release-notes.txt').write_text(f"<!-- rikkahub-personal-version-code: {code} -->\n\n非公开覆盖升级测试候选 {name}\n\n本次受控下载使用构建任务 {a.job} 的原始已签名 ARM64 APK。\n")
print(json.dumps(out,ensure_ascii=False))
