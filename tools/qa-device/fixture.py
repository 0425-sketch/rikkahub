"""Disposable Google APIs AVD. All settings, credentials, and generated data are synthetic."""
import base64
import json
import os
from pathlib import Path
import subprocess
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PKG = 'me.rerere.rikkahub.personal'
MODEL = 'abc00000-0000-4000-8000-000000000001'
PORT = 18414
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAALUlEQVR4nGMU87rFQEvARFPTRy0YtWDUglELRi0YtWDUglELRi0YtWDUAioCAG0YAXq0bZtrAAAAAElFTkSuQmCC')
LOG = Path(os.environ.get('RUNNER_TEMP','/tmp')) / 'fixture-events.jsonl'


def adb(*args, data=None):
    return subprocess.check_output(['adb', *args], input=data, timeout=90)


def varint(number):
    out = bytearray()
    while number >= 128:
        out.append(number % 128 + 128)
        number //= 128
    return bytes(out) + bytes([number])


def field(number, data):
    return varint(number * 8 + 2) + varint(len(data)) + data


def seed():
    assert adb('shell', 'getprop', 'ro.kernel.qemu.avd_name').strip() == b'release_qa'
    assert b'versionCode=209 ' in adb('shell','dumpsys','package',PKG)
    # Start from the actual published 209 and populate only synthetic test preferences.
    adb('shell','am','force-stop',PKG)
    base = f'http://127.0.0.1:{PORT}/v1'
    fields = {'providers':[{'type':'openai','id':'abc00000-0000-4000-8000-000000000002','name':'QA text','apiKey':'qa-fixture','baseUrl':base,'models':[{'id':MODEL,'modelId':'synthetic-text','displayName':'Mock text'}]}],
              'media_generation_providers':[{'type':'openai_platform_images','name':'QA OpenAI Platform','apiKey':'qa-fixture','models':[{'modelId':'gpt-image-2.6-sunburst','kind':'IMAGE','displayName':'Mock image'}]}],
              'chat_model':MODEL,'fast_model':MODEL,'prompt_enhancement_model':MODEL, 'display_setting':{'userNickname':'QA-Synthetic','showTokenUsage':True},
              'webdav_config':{'items':['DATABASE','FILES']}}
    fields['providers'].append({'type':'chatgpt','id':'abc00000-0000-4000-8000-000000000003','name':'QA SIWC'})
    entries = []
    for name,value in fields.items():
        val=field(5,(value if isinstance(value,str) else json.dumps(value)).encode())
        entries.append(field(1,field(1,name.encode()) + field(2,val)))
    adb('shell','run-as',PKG,'mkdir','-p','files/datastore')
    adb('shell','-T',f'run-as {PKG} sh -c "cat > files/datastore/settings.preferences_pb"',data=b''.join(entries))
    adb('reverse',f'tcp:{PORT}',f'tcp:{PORT}')


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):
        pass

    def do_CONNECT(self):
        if self.path != 'api.openai.com:443' or self.headers.get('Proxy-Authorization'):
            self.send_error(403)
            return
        directory=Path(os.environ['RUNNER_TEMP'])
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(directory/'origin-leaf.pem',directory/'origin-leaf.key')
        self.send_response_only(200);self.end_headers();self.wfile.flush()
        with ctx.wrap_socket(self.connection,server_side=True) as stream:
            stream.settimeout(30)
            Handler(stream,self.client_address,self.server)
        self.close_connection=True

    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path in ('/v1/chat/completions', f'http://127.0.0.1:{PORT}/v1/chat/completions'):
            prompt=body['messages'][-1]['content']
            text=prompt if isinstance(prompt,str) else str(prompt)
            content='Paint a QA blue square' if 'picture' in text else 'Polish the QA message'
            event='image-enhancement' if 'picture' in text else 'chat-enhancement'
            obj={'id':'qa','object':'chat.completion','model':'synthetic-text','choices':[{'index':0,'message':{'role':'assistant','content':content},'finish_reason':'stop'}],
                 'usage':{'prompt_tokens':122,'completion_tokens':25,'total_tokens':147,'prompt_tokens_details':{'cached_tokens':18}}}
            if body.get('stream'):
                chunks=[{'choices':[{'index':0,'delta':{'role':'assistant','content':content},'finish_reason':None}]},
                        {'choices':[{'index':0,'delta':{},'finish_reason':'stop'}]}, {'choices':[],'usage':obj['usage']}]
                data=(''.join('data: '+json.dumps(c)+'\n\n' for c in chunks)+'data: [DONE]\n\n').encode()
                ctype='text/event-stream'
            else:
                data=json.dumps(obj).encode()
                ctype='application/json'
        elif (self.path == '/v1/images/generations' and self.headers.get('Authorization') == 'Bearer qa-fixture'
              and self.headers.get('Host') == 'api.openai.com' and body['prompt'] == 'Paint a QA blue square'):
            event='openai-image-generation'
            data=json.dumps({'created':100,'data':[{'b64_json':base64.b64encode(PNG).decode()}],'usage':{'total_tokens':7}}).encode()
            ctype='application/json'
        else:
            self.send_error(400);return
        with LOG.open('a') as file:
            print(json.dumps({'event':event,'model':body.get('model'),'path':self.path}),file=file)
        self.send_response(200)
        self.send_header('content-type',ctype)
        self.send_header('content-length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__=='__main__':
    import sys
    if sys.argv[1]=='seed':
        seed()
    else:
        port = PORT if sys.argv[1]=='serve' else PORT+1
        ThreadingHTTPServer(('127.0.0.1',port),Handler).serve_forever()
