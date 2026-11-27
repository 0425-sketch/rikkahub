"""Exercise production Compose screens through an isolated emulator's accessibility tree."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from xml.etree import ElementTree
import zipfile
from fixture import adb, PKG, PNG, LOG

OUT = Path(os.environ['RUNNER_TEMP']) / 'device-results'
OUT.mkdir(exist_ok=True)
ROUTE = PKG + '/me.rerere.rikkahub.RouteActivity'
def pause(): time.sleep(.45)
def sh(*args): return adb('shell',*args).decode().strip()
def key(k): sh('input','keyevent',k)
def screen():
    for attempt in range(4):
        try:
            sh('uiautomator','dump','--compressed','/sdcard/ui.xml')
            data=adb('exec-out','cat','/sdcard/ui.xml')
            (OUT/'last.xml').write_bytes(data)
            return [n.attrib for n in ElementTree.fromstring(data).iter('node')]
        except (subprocess.CalledProcessError, ElementTree.ParseError, RuntimeError):
            time.sleep(1)
    raise RuntimeError('Could not read foreground window')
def find(nodes, label):
    for a in reversed(nodes):
        if label == a.get('class') or label == a.get('resource-id') or label in (a.get('text','')+'\n'+a.get('content-desc','')).splitlines():
            return a
    return None
def labels(nodes):
    return [(a.get('text'),a.get('content-desc'),a.get('resource-id'),a.get('class')) for a in nodes if a.get('text') or a.get('content-desc') or a.get('clickable')=='true']
def wait(label, retries=9):
    for _ in range(retries):
        nodes=screen()
        node=find(nodes,label)
        if node: return node
        time.sleep(.5)
    raise AssertionError(f'Missing {label}: {labels(nodes)}')
def touch(node,long=False):
    x,y,r,b=map(int,re.findall(r'\d+',node['bounds']))
    cx,cy=str((x+r)//2),str((y+b)//2)
    if long: sh('input','swipe',cx,cy,cx,cy,'850')
    else: sh('input','tap',cx,cy)
    pause()
def tap(label): touch(wait(label))
def edit(value):
    touch(wait('android.widget.EditText'))
    sh('input','text',value.replace(' ','%s'))
    key('KEYCODE_BACK')
def scrolltap(label,long=False):
    for _ in range(12):
        nodes=screen()
        node=find(nodes,label)
        if node:
            touch(node,long)
            return
        sh('input','swipe','360','1100','360','470','420'); pause()
    raise AssertionError(f'Cannot scroll to {label}: {labels(nodes)}')
def mark(result):
    print('PASS '+result,flush=True)
    with (OUT/'passed.txt').open('a') as file: print(result,file=file)
def launch(action=None):
    sh('am','force-stop',PKG)
    sh('am','start','-W',*(['-a',action] if action else []),'-n',ROUTE)
def drawer():
    launch()
    tap('Messages')
    wait('QA-Synthetic')
def event(label):
    for _ in range(16):
        if LOG.is_file() and any(json.loads(row)['event']==label for row in LOG.read_text().splitlines()): return
        time.sleep(.3)
    raise AssertionError('Missing fixture HTTP request '+label)
def prompt():
    drawer(); tap('Chat prompt enhancement')
    edit('rewrite synthetic text')
    tap('Enhance')
    wait('Polish the QA message')
    event('chat-enhancement')
    mark('Chat prompt rewritten by fixture text provider')
def media():
    launch('me.rerere.rikkahub.action.MEDIA_CREATION')
    edit('draw a synthetic picture')
    tap('Enhance prompt')
    wait('Paint a QA blue square')
    event('image-enhancement')
    mark('Image prompt rewritten by fixture text provider')
    tap('Generate')
    event('openai-image-generation')
    time.sleep(2)
    mark('OpenAI Platform adapter sent the enhanced prompt to the isolated TLS fixture /v1/images/generations')
def export_backup():
    drawer(); tap('Settings'); scrolltap('Data Backup')
    # Touch the action's supporting line to distinguish it from a same-named group heading.
    scrolltap('The ZIP may contain API keys. Store it somewhere private.')
    tap('SAVE')
    for _ in range(25):
        names=sh('ls','/sdcard/Download').splitlines()
        zips=[f for f in names if f.startswith('rikkahub_backup_') and f.endswith('.zip')]
        if zips:
            data=adb('exec-out','cat','/sdcard/Download/'+zips[-1])
            if zipfile.is_zipfile(io.BytesIO(data)): break
        time.sleep(1)
    else: raise AssertionError('No complete normal-page backup ZIP')
    with zipfile.ZipFile(io.BytesIO(data)) as src:
        assert src.testzip() is None
        assert src.read('rikka_hub.db').startswith(b'SQLite format 3\x00')
        settings=json.loads(src.read('settings.json'))
        assert settings['displaySetting']['userNickname']=='QA-Synthetic'
        print('Normal ZIP contains settings, Room database and',len(src.namelist()),'entries',flush=True)
        image_paths=[p for p in src.namelist() if p.endswith('.png') and src.read(p)==PNG]
        if LOG.is_file() and 'openai-image-generation' in LOG.read_text():
            assert image_paths,'Missing saved result of OpenAI media generation in normal backup'
            mark('Generated PNG bytes found in backup of signed app data')
        # Leave the ordinary export intact. Give the recovery copy a distinct, observable nickname.
        recovered=io.BytesIO()
        with zipfile.ZipFile(recovered,'w',zipfile.ZIP_DEFLATED) as out:
            for name in src.namelist():
                if name=='settings.json':
                    settings['displaySetting']['userNickname']='QA-Restored'
                    out.writestr(name,json.dumps(settings,ensure_ascii=False))
                else: out.writestr(name,src.read(name))
    with tempfile.NamedTemporaryFile() as f:
        f.write(recovered.getvalue());f.flush(); adb('push',f.name,'/sdcard/Download/qa-restoration.zip')
    mark('Normal local export read back with settings and Room data')
def rescue():
    drawer(); tap('Settings'); scrolltap('About'); touch(wait('Version'),long=True)
    wait('Debug Mode')
    mark('About version long press opened Debug Mode on installed APK')
    scrolltap('崩溃')
    time.sleep(2)
    # The app's crash-handler preference was set by the deliberate, built-in test crash.
    launch()
    wait('Safe Mode'); mark('Real local crash handler redirected launch to Safe Mode')
    scrolltap('Import from File'); tap('Confirm')
    nodes=screen()
    if not find(nodes,'qa-restoration.zip'):
        drawer_button=find(nodes,'Show roots') or find(nodes,'Show navigation drawer')
        if drawer_button:
            touch(drawer_button);tap('Downloads')
    tap('qa-restoration.zip')
    tap('Restart App')
    time.sleep(1);launch();tap('Messages')
    wait('QA-Restored')
    mark('Safe Mode restored the controlled ZIP, restarted, and showed the restored setting')

if __name__=='__main__':
    assert sh('getprop','ro.kernel.qemu.avd_name')=='release_qa'
    try:
        failed=[]
        for step in (prompt,media,export_backup,rescue):
            print('Running',step.__name__,flush=True)
            try:
                step()
            except Exception as exc:
                failed.append(step.__name__)
                print(str(exc),flush=True)
                (OUT/(step.__name__+'-fail.png')).write_bytes(adb('exec-out','screencap','-p'))
        if failed: raise AssertionError('Failed features: '+', '.join(failed))
    except BaseException:
        (OUT/'failed.png').write_bytes(adb('exec-out','screencap','-p'))
        try: print(labels(screen()),flush=True)
        except Exception: pass
        raise
