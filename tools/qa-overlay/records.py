from device import *
import shlex,re
def sql(q):
    return shell('sqlite3 '+shlex.quote('/data/data/'+PKG+'/databases/rikka_hub')+' '+shlex.quote(q))
aid='0950e2dc-9bd5-4801-afa3-aa887aa36b4e'
mem_path='migration/fixture.md'
def memory():
    confirm()
    assert 'versionCode=209 ' in shell('dumpsys package '+PKG)
    print(sql(f"INSERT OR IGNORE INTO memory_file(assistant_id,path,content,updated_at) VALUES ('{aid}','{mem_path}','Fictional blue square memory',1700000000000)"))
def capture():
    confirm()
    data=json.loads((P/'chat.json').read_text()); cid=data['id']; uploaded=data['image']
    pkg=shell('dumpsys package '+PKG)
    entry=lambda pattern:re.search(pattern,pkg).group(1)
    snapshot=req('/conversations/'+cid)
    urls=[p.get('url') for n in snapshot['messages'] for m in n['messages'] for p in m['parts'] if p.get('type')=='image']
    assert uploaded['url'] in urls
    blob=urllib.request.build_opener(urllib.request.ProxyHandler({})).open(f"http://127.0.0.1:18413/api/files/id/{uploaded['id']}",timeout=60).read()
    assert blob==(P/'sample.png').read_bytes()
    dpath='/data/data/'+PKG
    raw=adb('exec-out','cat',dpath+'/files/datastore/settings.preferences_pb',text=False)
    p=prefs(raw)
    assert b'MIGRATION-209' in p['display_setting'] and b'migration-fixture' in p['providers']
    assert sql(f"SELECT content FROM memory_file WHERE assistant_id='{aid}' AND path='{mem_path}'")=='Fictional blue square memory'
    rows=sql(f"SELECT prompt_tokens,completion_tokens,cached_tokens,prompt_tokens_reported,cached_tokens_reported FROM usage_record WHERE source='CHAT' AND conversation_id='{cid}' ORDER BY id").splitlines()
    assert '120|20|30|1|1' in rows and set(rows).issubset({'120|20|30|1|1','77|0|0|1|0'}),rows
    out={'version':entry(r'versionCode=(\d+)'),'uid':entry(r'userId=(\d+)'),'first_install':entry(r'firstInstallTime=([^\r\n]+)'),'files_dir':entry(r'dataDir=([^\r\n]+)'),'attachment_sha256':hashlib.sha256(blob).hexdigest(),'nickname':'MIGRATION-209','memory':'Fictional blue square memory','chat_id':cid,'messages':len(snapshot['messages']),'usage_rows':rows,'rate':25,'db_schema_version':sql('PRAGMA user_version')}
    print(json.dumps(out))
    label=sys.argv[2]
    if label!='209':
        old=json.loads((P/'snapshot-209.json').read_text())
        for key in ['uid','first_install','files_dir','attachment_sha256','nickname','memory','chat_id','db_schema_version']:
            assert old[key]==out[key],key
        assert out['usage_rows'][:len(old['usage_rows'])]==old['usage_rows']
    (P/f'snapshot-{label}.json').write_text(json.dumps(out,indent=2))
if __name__=='__main__':
    (memory if sys.argv[1]=='memory' else capture)()
