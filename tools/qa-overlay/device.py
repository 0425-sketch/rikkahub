"""Local disposable AVD only. No real data or credentials."""
import subprocess as sp, os, sys, pathlib as pl,json,time,uuid,io,hashlib,urllib.request
from PIL import Image,ImageDraw
P=pl.Path(os.environ['RQA_DATA'])
PKG='me.rerere.rikkahub.personal'
SERIAL='emulator-5554'
ADB='adb'
def adb(*args, data=None, text=True):
    r=sp.run([ADB,'-s',SERIAL,*args],input=data,stdout=sp.PIPE,check=True,timeout=210)
    return r.stdout.decode().strip() if text else r.stdout
def shell(s): return adb('shell',s)
def confirm():
    assert shell('getprop ro.kernel.qemu')=='1'
    assert 'rikkaoverlay' in adb('emu','avd','name').splitlines()
def launch(): print(shell('am start -n '+PKG+'/me.rerere.rikkahub.RouteActivity'))
def wireint(n):
    out=bytearray()
    while n>=128:
        out.append((n%128)+128); n//=128
    out.append(n)
    return bytes(out)
def field(n,b):
    return wireint(n*8+2)+wireint(len(b))+b
def read_fields(data):
    src=io.BytesIO(data)
    def v():
        i=0;out=0
        while True:
            b=src.read(1)[0]
            out+=(b & 127)<<(7*i)
            if b<128:return out
            i+=1
    while src.tell()<len(data):
        tag=v()
        if tag%8!=2: raise ValueError(tag)
        yield tag//8,src.read(v())
def prefs(data):return {dict(read_fields(entry))[1].decode():dict(read_fields(entry))[2] for key,entry in read_fields(data) if key==1}
def writeprefs(items):return b''.join(field(1,field(1,key.encode())+field(2,val)) for key,val in items.items())
def pv(value):
    if isinstance(value,bool):return b'\x08'+wireint(int(value))
    if isinstance(value,int):return b'\x18'+wireint(value)
    return field(5,(value if isinstance(value,str) else json.dumps(value)).encode())
db='databases/rikka_hub'
def seed_preferences():
    confirm()
    assert 'versionCode=209 ' in shell('dumpsys package '+PKG)
    shell('am force-stop '+PKG)
    path='files/datastore/settings.preferences_pb'
    marker=b'urn:uuid:00000000-0000-4000-8000-000000000209'
    adb('shell','-T',f'run-as {PKG} sh -c "mkdir -p no_backup && cat > no_backup/chatgpt-host-id"',data=marker)
    assert adb('exec-out','run-as',PKG,'cat','no_backup/chatgpt-host-id',text=False)==marker
    raw=adb('exec-out','run-as',PKG,'cat',path,text=False)
    obj=prefs(raw)
    model='eee00000-0000-4000-8000-000000000002'
    provider={'type':'openai','name':'Local synthetic provider','id':'eee00000-0000-4000-8000-000000000001','apiKey':'fixture-only','baseUrl':'http://127.0.0.1:18412/v1','models':[{'id':model,'modelId':'migration-fixture','displayName':'Migration fixture','inputModalities':['TEXT','IMAGE']}]}
    setup={'web_server_enabled':True,'web_server_localhost_only':True,'web_server_jwt_enabled':False,'web_server_port':18413,'chat_model':model,'fast_model':model,'providers':[provider],'enable_auto_title':False,'enable_suggestion':False,'prompt_enhancement_model':model,'display_setting':{'userNickname':'MIGRATION-209','showTokenUsage':True}}
    obj.update({k:pv(val) for k,val in setup.items()})
    adb('shell','-T',f'run-as {PKG} sh -c "cat > {path}"',data=writeprefs(obj))
    assert prefs(adb('exec-out','run-as',PKG,'cat',path,text=False))==obj
    adb('reverse','tcp:18412','tcp:18412')
    adb('forward','tcp:18413','tcp:18413')
    launch()
def req(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    rq=urllib.request.Request('http://127.0.0.1:18413/api'+path,data=data,headers={'Content-Type':'application/json'})
    return json.load(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(rq,timeout=120))
def upload():
    png=Image.new('RGB',(48,48),'#1d4ed8'); d=ImageDraw.Draw(png);d.rectangle((12,12,35,35),fill='#fcba03'); png.save(P/'sample.png')
    cmd=['curl','--noproxy','*','-fsS','-F','file=@'+str(P/'sample.png')+';type=image/png','http://127.0.0.1:18413/api/files/upload']
    return json.loads(sp.check_output(cmd))['files'][0]
def chat(first, missing=False):
    f=P/'chat.json'
    if first:
        state={'id':str(uuid.uuid4()),'image':upload()}
        f.write_text(json.dumps(state))
    state=json.loads(f.read_text()); cid=state['id']; url='/conversations/'+cid
    parts=[{'type':'text','text':'Read the synthetic blue image' if first else 'After update verification '+str(time.time())}]
    if missing:parts[0]['text']='MISSING-OUTPUT-FIXTURE'
    if first:parts.append({'type':'image','url':state['image']['url']})
    before=0 if first else len(req(url)['messages'])
    print(req(url+'/messages',{'parts':parts}),flush=True)
    deadline=time.monotonic()+220; partial=False
    while time.monotonic()<deadline:
        snapshot=req(url); msgs=snapshot['messages']
        if len(msgs)>=before+2:
            msg=msgs[-1]['messages'][-1]
            joined=' '.join(p.get('text','') for p in msg['parts'])
            if 'Synthetic reply' in joined and 'ARM64' not in joined:partial=True
            if 'ARM64' in joined and msg.get('usage') and not snapshot.get('isGenerating',False):
                usage=msg['usage']
                if missing:
                    assert usage['promptTokens']==77
                    assert not usage.get('completionTokensReported', usage.get('completionTokens',0)>0)
                    assert not usage.get('cachedTokensReported', usage.get('cachedTokens',0)>0)
                else:
                    assert all(v==usage.get(k) for k,v in [('promptTokens',120),('completionTokens',20),('cachedTokens',30)]), usage
                print(json.dumps({'first':first,'partial_reply_observed':partial,'messageCount':len(msgs),'usage':msg['usage'],'text':joined}),flush=True); return
        time.sleep(0.5)
    raise RuntimeError(str(snapshot)[:2000])
if __name__=='__main__':
    confirm()
    if sys.argv[1]=='prefs':seed_preferences()
    elif sys.argv[1]=='chat':chat(sys.argv[2]=='first',sys.argv[2]=='missing')
    elif sys.argv[1]=='read':print(json.dumps(req('/conversations/'+json.loads((P/'chat.json').read_text())['id'])))
