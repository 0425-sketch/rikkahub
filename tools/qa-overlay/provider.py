from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json,time

class Provider(BaseHTTPRequestHandler):
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path != '/v1/chat/completions' or data.get('model') != 'migration-fixture':
            self.send_error(404); return
        usage={'prompt_tokens':120,'completion_tokens':20,'total_tokens':140,'prompt_tokens_details':{'cached_tokens':30}}
        if 'MISSING-OUTPUT-FIXTURE' in json.dumps(data.get('messages', [])[-1:]):
            usage={'prompt_tokens':77}
        stream=data.get('stream',False)
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream' if stream else 'application/json')
        self.end_headers()
        if not stream:
            self.wfile.write(json.dumps({'id':'fixture','model':data['model'],'choices':[{'index':0,'message':{'role':'assistant','content':'Synthetic enhanced prompt'},'finish_reason':'stop'}],'usage':usage}).encode())
            return
        for delta,finish in [({'role':'assistant','content':'Synthetic reply'},None),({'content':' from ARM64 migration fixture'},None),({},'stop')]:
            event={'id':'fixture','object':'chat.completion.chunk','model':data['model'],'choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
            self.wfile.write(('data: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush(); time.sleep(1.5)
        self.wfile.write(('data: '+json.dumps({'id':'fixture','object':'chat.completion.chunk','choices':[],'usage':usage})+'\n\ndata: [DONE]\n\n').encode())
ThreadingHTTPServer(('127.0.0.1',18412),Provider).serve_forever()
