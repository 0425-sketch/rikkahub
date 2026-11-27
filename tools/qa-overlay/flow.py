"""Exercise the actual update UI on this job's fresh AVD; observe the system install and retained package."""
from device import *
import subprocess,re
from xml.etree import ElementTree as ET
def nodes():
    xml=shell('uiautomator dump /sdcard/window.xml >/dev/null 2>&1 && cat /sdcard/window.xml')
    return list(ET.fromstring(xml).iter('node'))
def label(node):
    return ' '.join(node.get(k,'') for k in ('text','content-desc')).strip()
def summary(ns): return [label(x) for x in ns if label(x)]
def tap(node):
    print('Tap',node.get('resource-id'),label(node)[:160],node.get('bounds'),flush=True)
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
    shell(f'input tap {(x1+x2)//2} {(y1+y2)//2}')
def uiwait(predicate,seconds=90):
    end=time.monotonic()+seconds;last=[];error=''
    while time.monotonic()<end:
        try:
            ns=nodes();last=summary(ns);result=predicate(ns)
            if result is not None:return result
        except (ET.ParseError,sp.SubprocessError) as e:error=repr(e)
        time.sleep(1)
    raise AssertionError(f'UI timeout: {last} {error}')
def first(ns,fn):return next((n for n in ns if fn(label(n))),None)
def activate(fixture, extra=()):
    if fixture:
        proof,apk,notes=fixture
        origin=P/'origin'/'fixture.py'
        log=(P/'fixture.log').open('ab')
        proc=sp.Popen([sys.executable,'-u',origin,'--proof',proof,'--apk',apk,'--notes',notes,*extra],stdout=log,stderr=log)
        time.sleep(1)
        assert proc.poll() is None, (P/'fixture.log').read_text()
        shell('settings put global http_proxy 127.0.0.1:18734')
    else:proc=None
    shell('am force-stop '+PKG)
    launch()
    return proc
def start(fixture, extra=()):
    proc=activate(fixture,extra)
    def card(ns):
        k=first(ns,lambda x: x.startswith('New version found '+version))
        if k is not None:return k
        k=first(ns,lambda x:x.lower() in ('confirm','ok','while using the app','allow','got it'))
        if k is not None:tap(k)
        elif not any('MIGRATION-209' in x for x in summary(ns)):
            menu=first(ns,lambda x:x=='Messages')
            if menu is not None:tap(menu)
            else:shell('input swipe 48 700 660 700 350')
        return None
    tap(uiwait(card,110))
    def file_row(ns):
        labels=summary(ns)
        row=first(ns,lambda x:x.startswith('rikkahub-'+version) and '-arm64.apk' in x)
        if row is not None:
            assert any(expected_notice in x for x in labels),labels
            print(json.dumps({'drawer_version':version,'sheet_note':expected_notice,'selected':label(row)}),flush=True)
        return row
    tap(uiwait(file_row))
    return proc
def grant_install():
    packages=set(shell('pm list packages').splitlines())
    for source in ['me.rerere.rikkahub.personal','com.android.documentsui','com.google.android.documentsui','com.android.providers.downloads.ui','com.android.providers.downloads']:
        if 'package:'+source in packages:shell('appops set '+source+' REQUEST_INSTALL_PACKAGES allow')
def install(code):
    expected=f'versionCode={code} '
    attempts=0; observed=None
    def step(ns):
        nonlocal attempts,observed
        attempts+=1
        if expected in shell('dumpsys package '+PKG):
            if observed is None:observed=time.monotonic()
            open_app=first(ns,lambda x:x.strip().lower()=='open')
            if open_app is not None:
                print('Android confirmed installation',summary(ns),flush=True)
                tap(open_app);time.sleep(1);return True
            if time.monotonic()-observed>12:return True
            return None
        labels=summary(ns)
        if any(('not installed' in l.lower() or 'problem parsing' in l.lower()) for l in labels):
            raise AssertionError(labels)
        if attempts%3==1:
            print('System surface',shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1"),labels,flush=True)
        if any(x.lower() in ('open with','complete action using') for x in labels):
            installer=first(ns,lambda x:x.lower()=='package installer')
            if installer is not None:
                tap(installer);time.sleep(.35)
                once=first(nodes(),lambda x:x.lower()=='just once')
                if once is not None and once.get('enabled')=='true':tap(once)
                return None
        control=first(ns,lambda x:x.strip().lower() in ('install','update','continue','install anyway','settings','just once','allow from this source'))
        if control is not None:
            print('Installer:',label(control),flush=True);tap(control)
            return None
        row=first(ns,lambda x:'rikkahub-'+version in x)
        if row is not None and any('Downloads'==x for x in labels):
            print('Downloads items',[(x.get('resource-id'),x.get('bounds')) for x in ns if 'item' in x.get('resource-id','')],flush=True)
            tile=next((x for x in ns if x.get('resource-id','').endswith('/thumbnail')),row)
            tap(tile);time.sleep(.13);tap(tile)
        if 'Download complete' in labels and row is not None:
            tap(row)

        return None
    uiwait(step,120)
def downloads(apk):
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    end=time.monotonic()+180; path=None
    while time.monotonic()<end:
        for filename in shell('ls /sdcard/Download/').splitlines():
            if filename.startswith('rikkahub-'+version) and filename.endswith('.apk'):
                value=shell('sha256sum /sdcard/Download/'+filename).split()[0]
                if value==digest:path=filename;break
        if path:break
        time.sleep(1)
    assert path, 'No verified Download Manager APK'
    print('Download Manager completed',path,digest,flush=True)
    # 209's notification sets the original downloader as install source. Open the actual Download
    # directory on the test phone, exactly like an authorized OEM file manager or Material Files.
    shell('input keyevent KEYCODE_WAKEUP');shell('wm dismiss-keyguard')
    shell('am start -W -n me.zhanghai.android.files/.filelist.FileListActivity')
    opened=False
    def from_files(ns):
        nonlocal opened
        ls=summary(ns)
        print('File manager',ls,flush=True)
        target=first(ns,lambda x:x.startswith('rikkahub-'+version))
        if target is not None:
            tap(target);return True
        folder=first(ns,lambda x:x.strip().lower() in ('download','downloads'))
        if folder is not None and not opened:
            tap(folder);opened=True;return None
        accept=first(ns,lambda x:x.lower() in ('allow','continue','got it','ok'))
        if accept is not None:tap(accept)
        else:shell('input swipe 330 1100 330 480 400')
        return None
    uiwait(from_files,60)
def ready():
    for attempt in range(3):
        if attempt:
            print('Relaunching the already installed package',shell('dumpsys activity activities | grep mResumedActivity | head -1'),flush=True)
            shell('am force-stop '+PKG)
        print(shell('am start -W -n '+PKG+'/me.rerere.rikkahub.RouteActivity'),flush=True)
        until=time.monotonic()+20
        while time.monotonic()<until:
            try:req('/conversations');return
            except Exception:time.sleep(1)
    raise AssertionError('app loopback server failed to start after normal relaunch')
if __name__=='__main__':
    confirm()
    mode=sys.argv[1];stage=P/'stage';grant_install()
    code=211 if mode=='next' else 210
    version='3.3.0-personal.'+('11' if code==211 else '10')
    expected_notice='非公开覆盖升级测试候选' if code==211 else 'RikkaHub Release 209'
    apk=stage/('next-candidate.apk' if code==211 else 'primary.apk')
    loc=(P/'next') if code==211 else stage
    if mode=='public':
        proxy=shell('settings get global http_proxy').strip()
        assert proxy in ('', 'null', ':0'), proxy
        assert not (P/'fixture.log').exists()
        print('Fresh AVD has no fixture or proxy. Public GitHub source:', 'https://api.github.com/repos/0425-sketch/rikkahub/releases',flush=True)
    proc=start(None if mode=='public' else (loc/'build-provenance.json', apk,loc/'release-notes.txt'))
    try:
        if code==210:downloads(apk)
        install(code)
    finally:
        if proc:
            proc.terminate();proc.wait()
            shell('settings put global http_proxy :0')
            shell('settings delete global http_proxy')
    ready()
    from records import capture
    sys.argv=['records.py','capture',str(code)+'-before'];capture()
    chat(False)
    sys.argv[-1]=str(code)+'-after';capture()
    a=json.loads((P/f'snapshot-{code}-before.json').read_text())
    b=json.loads((P/f'snapshot-{code}-after.json').read_text())
    assert len(b['usage_rows'])==len(a['usage_rows'])+1 and b['messages']==a['messages']+2

    if code==210:
        chat(False,missing=True)
        sys.argv[-1]=str(code)+'-missing'
        capture()
        partial=json.loads((P/f'snapshot-{code}-missing.json').read_text())
        assert partial['usage_rows'][-1]=='77|0|0|1|0'
