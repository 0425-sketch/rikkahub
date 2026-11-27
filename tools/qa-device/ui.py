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
        if label == a.get('class') or label == a.get('resource-id') or label.casefold() in (a.get('text','')+'\n'+a.get('content-desc','')).casefold().splitlines():
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
    if os.environ.get('QA_TARGET')=='candidate': assert find(screen(),'[开发模式]') is None
def settings():
    drawer()
    nodes=screen()
    item=find(nodes,'Settings')
    if not item:
        # The drawer renders the Settings icon without an accessibility description.
        # On the real screen this is the last circular button in the Stats row.
        stats=find(nodes,'统计数据')
        assert stats,labels(nodes)
        sx,sy,sr,sb=map(int,re.findall(r'\d+',stats['bounds']))
        # ChatDrawer.kt: 300dp sheet, 8dp column and row insets, 40dp circle.
        # The icon has no semantic node on compressed Android 11 accessibility trees.
        density=int(re.findall(r'(?:Physical|Override) density: (\d+)',sh('wm','density'))[-1])
        x=round((300 - 8 - 8 - 20) * density / 160)
        y=(sy+sb)//2
        print('Settings at end of drawer row',x,y,flush=True)
        sh('input','tap',str(x),str(y))
    else:
        touch(item)
    wait('Settings')

def event(label):
    for _ in range(16):
        if LOG.is_file() and any(json.loads(row)['event']==label for row in LOG.read_text().splitlines()): return
        time.sleep(.3)
    raise AssertionError('Missing fixture HTTP request '+label)
def siwc():
    settings()
    scrolltap('Providers')
    scrolltap('QA SIWC')
    scrolltap('Continue with ChatGPT')
    # Inspect the Android launch without logging OAuth parameters or using credentials.
    time.sleep(2)
    first = adb('shell','dumpsys','activity','activities')
    system_log = adb('shell','logcat','-d','-b','system','-s','ActivityTaskManager:I','*:S')
    assert b'auth.openai.com' in first + system_log, 'No system launch for the expected OAuth host'
    host_path='/data/user/0/'+PKG+'/no_backup/chatgpt-host-id'
    assert re.fullmatch(r'urn:uuid:[0-9a-f-]{36}',sh('cat',host_path)), 'Missing local SIWC installation ID'
    mark('SIWC built the local installation identity and launched the authorization browser, with no sign-in')

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
    settings(); scrolltap('Data Backup')
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
        saved_settings=json.loads(src.read('settings.json'))
        assert saved_settings['displaySetting']['userNickname']=='QA-Synthetic'
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
                    saved_settings['displaySetting']['userNickname']='QA-Restored'
                    out.writestr(name,json.dumps(saved_settings,ensure_ascii=False))
                else: out.writestr(name,src.read(name))
    with tempfile.NamedTemporaryFile() as f:
        f.write(recovered.getvalue());f.flush(); adb('push',f.name,'/sdcard/Download/qa-restoration.zip')
    mark('Normal local export read back with settings and Room data')
def rescue():
    settings(); scrolltap('About this app'); scrolltap('Version',long=True)
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
    # Android 11 DocumentsUI uses a touch handler on the document row. Its title
    # is a non-clickable child, and its preview icon opens a different action.
    grid=screen()
    toggle=find(grid,'List view')
    if toggle: touch(toggle)
    time.sleep(.8)
    for operation in ('body','double','keyboard','long'):
        nodes=screen()
        if find(nodes,'Restart App'): break
        confirm=find(nodes,'Open') or find(nodes,'Select')
        if confirm and confirm.get('enabled')=='true': touch(confirm); continue
        title=find(nodes,'qa-restoration.zip')
        if title is None: break
        x,y,r,b=map(int,re.findall(r'\d+',title['bounds']))
        rects=[]
        for n in nodes:
            if not n.get('resource-id','').endswith('/item_root'): continue
            left,top,right,bottom=map(int,re.findall(r'\d+',n['bounds']))
            if left<=x<right and top<=y<bottom:
                rects.append((left,top,right,bottom))
        assert len(rects)==1,(title,rects)
        left,top,right,bottom=rects[0]
        # Stay in the center of the item's main surface, away from preview controls.
        tx,ty=str((left+right)//2),str((top+bottom)//2)
        print('Android picker',operation,rects[0],title['enabled'],flush=True)
        if operation=='body': sh('input','tap',tx,ty)
        if operation=='double': sh(f'input tap {tx} {ty}; input tap {tx} {ty}')
        if operation=='keyboard':
            sh('input','tap',tx,ty); key('KEYCODE_DPAD_CENTER')
        if operation=='long': sh('input','swipe',tx,ty,tx,ty,'800')
        time.sleep(1)
    tap('Restart App')
    time.sleep(1);launch();tap('Messages')
    wait('QA-Restored')
    mark('Safe Mode restored the controlled ZIP, restarted, and showed the restored setting')

if __name__=='__main__':
    assert sh('getprop','ro.kernel.qemu.avd_name')=='release_qa'
    try:
        failed=[]
        for step in (prompt,media,siwc,export_backup,rescue):
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
