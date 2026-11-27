"""Generate a disposable invalid signer and exercise actual Release 1 rejection paths on a test AVD."""
import datetime,tempfile,shutil
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
import flow
from device import *

bt=Path(os.environ['ANDROID_HOME'])/'build-tools/37.0.0'
sdk=Path(os.environ['ANDROID_HOME'])/'platforms/android-35/android.jar'
def invalid_apks(directory):
    if not sdk.exists():
        platform=sorted((Path(os.environ['ANDROID_HOME'])/'platforms').glob('android-*/android.jar'))[-1]
    else: platform=sdk
    with tempfile.TemporaryDirectory(dir=directory) as scratch:
        scratch=Path(scratch)
        key=ec.generate_private_key(ec.SECP256R1())
        name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Disposable rejection fixture')])
        today=datetime.datetime.now(datetime.timezone.utc)
        cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(today-datetime.timedelta(days=1)).not_valid_after(today+datetime.timedelta(days=1)).sign(key,hashes.SHA256())
        (scratch/'cert').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (scratch/'key').write_bytes(key.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        for what,pkg in [('signer',PKG),('package','org.example.overlay.invalid')]:
            manifest=scratch/'AndroidManifest.xml'
            manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" android:versionCode="211" android:versionName="3.3.0-personal.11"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="30"/><application android:hasCode="false" android:debuggable="false"/></manifest>')
            subprocess=sp.run
            subprocess([bt/'aapt2','link','-I',platform,'--manifest',manifest,'-o',scratch/'u.apk'],check=True)
            subprocess([bt/'zipalign','-f','4',scratch/'u.apk',scratch/'a.apk'],check=True)
            subprocess([bt/'apksigner','sign','--key',scratch/'key','--cert',scratch/'cert','--v4-signing-enabled','false','--out',directory/(what+'.apk'),scratch/'a.apk'],check=True)
            subprocess([bt/'apksigner','verify','--print-certs',directory/(what+'.apk')],check=True)
def installed():
    return 'versionCode=210 ' in shell('dumpsys package '+PKG)
def run(case,original):
    assert installed()
    asset={'package':d/'package.apk','signer':d/'signer.apk','downloaded-downgrade':P/'baseline.apk'}.get(case, P/'stage/next-candidate.apk')
    p=dict(original)
    p['apk_size']=asset.stat().st_size
    with asset.open('rb') as f:p['apk_sha256']=hashlib.file_digest(f,'sha256').hexdigest()
    if case=='feed-downgrade':p['version_code']=209
    tagged=d/(case+'.json');tagged.write_text(json.dumps(p))
    note=d/(case+'.txt')
    note.write_text(f"Offline probe {case}\n\n<!-- rikkahub-personal-version-code: {p['version_code']} -->")
    flow.expected_notice='Offline probe '+case;flow.version=p['version_name']
    log=P/'fixture.log';log.touch(exist_ok=True);pos=log.stat().st_size
    def seen():
        with log.open('rb') as f:
            f.seek(pos)
            return f.read().decode()
    extra=[]
    if case=='digest':extra=['--corrupt-download', d/'signer.apk']
    absent=case in ('source','feed-downgrade')
    if case=='source':extra=['--foreign-url']
    device_fixture=(tagged,asset,note)
    proc=flow.activate(device_fixture,extra) if absent else flow.start(device_fixture,extra)
    try:
        if absent:
            def drawer(ns):
                if 'MIGRATION-209' in flow.summary(ns) and '"resource": "releases"' in seen():return True
                btn=flow.first(ns,lambda x:x=='Messages' or x.lower()=='confirm')
                if btn is not None:flow.tap(btn)
                return None
            flow.uiwait(drawer,70)
            time.sleep(3)
            ns=flow.nodes();assert not any('New version found' in x for x in flow.summary(ns))
            assert '"resource": "apk"' not in seen()
        else:
            def rejected(ns):
                if '"resource": "apk"' not in seen():return None
                labels=flow.summary(ns)
                if any('Could not verify or open' in x for x in labels):return True
                if any(x.startswith('rikkahub-'+flow.version) for x in labels) and not any('Downloading and verifying' in x for x in labels):
                    return True
                return None
            flow.uiwait(rejected,120)
            top=shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1")
            assert PKG in top,top
        assert installed()
        print(json.dumps({'rejected':case,'installed':210,'fetched_apk':'"resource": "apk"' in seen(),'update_offered':not absent}),flush=True)
    finally:
        proc.terminate();proc.wait()
        shell('settings put global http_proxy :0')
        shell('settings delete global http_proxy')
if __name__=='__main__':
    os.umask(0o077);confirm()
    d=P/'negative';d.mkdir()
    invalid_apks(d)
    proof=json.loads((P/'next/build-provenance.json').read_text())
    for case in ('signer','package','downloaded-downgrade','digest','source','feed-downgrade'):
        run(case,proof)
