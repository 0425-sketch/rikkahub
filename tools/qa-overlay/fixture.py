"""Loopback-only, offline emulator test origin. No forwarding, upstream network, or dynamic certs."""
import argparse,datetime,hashlib,http.server,json,os,pathlib,re,socketserver,ssl,sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID,NameOID
ROOT=pathlib.Path(__file__).resolve().parent
CA=ROOT/'offline-fixture-ca.crt'
LEAF=ROOT/'offline-fixture-leaf.crt'
KEY=ROOT/'offline-fixture-leaf.key'
REPO='0425-sketch/rikkahub'
FEED='/repos/'+REPO+'/releases?per_page=100&page=1'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--init-ca',action='store_true')
parser.add_argument('--proof',type=pathlib.Path)
parser.add_argument('--apk',type=pathlib.Path)
parser.add_argument('--notes',type=pathlib.Path)
parser.add_argument('--foreign-url',action='store_true',help='Offer a non-repository APK URL to test source rejection. Never fetch it.')
parser.add_argument('--port',type=int,default=18734)
parser.add_argument('--corrupt-download',type=pathlib.Path,help='Negative case only. Replaces bytes after validating canonical metadata against --apk.')
args=parser.parse_args()
def generate_fixture_cert():
    # Never persist a signing authority key. Only this fixed leaf can be used during the test.
    if CA.exists() or KEY.exists() or LEAF.exists(): raise SystemExit('fixture cert already initialized')
    ca=ec.generate_private_key(ec.SECP256R1()); leaf=ec.generate_private_key(ec.SECP256R1())
    period=datetime.datetime.now(datetime.timezone.utc)
    def name(subject): return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,subject)])
    ca_name=name('RikkaHub isolated QA origin')
    def make(subject,issuer,key):
        return (x509.CertificateBuilder().subject_name(subject).issuer_name(issuer).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(period-datetime.timedelta(days=1)).not_valid_after(period+datetime.timedelta(days=3)))
    def keyusage(ca): return x509.KeyUsage(True,False,False,False,False,ca,ca,None,None)
    ca_cert=(make(ca_name,ca_name,ca).add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
        .add_extension(keyusage(True),critical=True)
        .add_extension(x509.NameConstraints([x509.DNSName('github.com')],None),critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca.public_key()),critical=False)
        .sign(ca,hashes.SHA256()))
    leaf_cert=(make(name('RikkaHub offline QA endpoint'),ca_name,leaf).add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName('github.com'),x509.DNSName('api.github.com')]),critical=False)
        .add_extension(keyusage(False),critical=True).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca.public_key()),critical=False)
        .sign(ca,hashes.SHA256()))
    CA.write_bytes(ca_cert.public_bytes(serialization.Encoding.PEM))
    LEAF.write_bytes(leaf_cert.public_bytes(serialization.Encoding.PEM))
    with KEY.open('xb') as f:
        os.fchmod(f.fileno(),0o600)
        f.write(leaf.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    print('public QA CA sha256',ca_cert.fingerprint(hashes.SHA256()).hex(),flush=True)
if args.init_ca: generate_fixture_cert();sys.exit(0)
if not (args.proof and args.apk and args.notes): parser.error('--proof, --apk, --notes are required')
p=json.loads(args.proof.read_bytes())
body=args.notes.read_text()
assert re.fullmatch('[0-9a-f]{40}',p['source_commit'])
short=p['source_commit'][:12]
assert re.fullmatch(r'custom-\d+\.\d+\.\d+-personal-'+short+r'-\d+-\d+',p['tag'])
assert re.fullmatch(r'\d+\.\d+\.\d+-personal\.\d+',p['version_name'])
fn='rikkahub-'+p['version_name']+'-'+short+'-arm64.apk'
assert p['application_id']=='me.rerere.rikkahub.personal' and p['certificate_sha256']=='882c1cc87ccf36c2ac5eb178b8d8ef13ff74a3ac8b759b41aed6e48bddb4e1fd' and p['architectures']==['arm64-v8a']
assert f"<!-- rikkahub-personal-version-code: {p['version_code']} -->" in body
def file_digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
assert args.apk.stat().st_size==p['apk_size'] and file_digest(args.apk)==p['apk_sha256']
prefix='/'+REPO+'/releases/download/'+p['tag']+'/'
assets=[dict(name=n,state='uploaded',browser_download_url='https://github.com'+prefix+n,size=f.stat().st_size,digest='sha256:'+file_digest(f)) for n,f in ((fn,args.apk),('build-provenance.json',args.proof))]
if args.foreign_url:
    assets[0]['browser_download_url']='https://example.invalid/untrusted.apk'
release=dict(tag_name=p['tag'],target_commitish=p['source_commit'],draft=False,prerelease=False,published_at=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),body=body,assets=assets)
routes={('api.github.com',FEED):('releases',json.dumps([release],ensure_ascii=False).encode(),'application/json'),
        ('github.com',prefix+'build-provenance.json'):('provenance',args.proof,'application/json'),
        ('github.com',prefix+fn):('apk',args.corrupt_download or args.apk,'application/vnd.android.package-archive')}
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.minimum_version=ssl.TLSVersion.TLSv1_2
ctx.set_alpn_protocols(['http/1.1'])
ctx.load_cert_chain(LEAF,KEY)
class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_CONNECT(self):
        if self.path not in {'api.github.com:443','github.com:443'}:
            self.send_error(403);return
        if 'Proxy-Authorization' in self.headers:
            self.send_error(403);return
        self.allowed_host=self.path.rpartition(':')[0]
        self.send_response_only(200,'Connection Established');self.end_headers();self.wfile.flush()
        with ctx.wrap_socket(self.connection,server_side=True) as connection:
            connection.settimeout(35)
            class Origin(http.server.BaseHTTPRequestHandler):
                def log_message(self,*args):pass
                def do_GET(origin):
                    host=origin.headers.get('Host','').removesuffix(':443')
                    entry=routes.get((host,origin.path))
                    if host!=self.allowed_host or entry is None or 'Authorization' in origin.headers or 'Cookie' in origin.headers:
                        origin.send_error(403);return
                    label,obj,mime=entry
                    size=len(obj) if isinstance(obj,bytes) else obj.stat().st_size
                    offset=0
                    partial=origin.headers.get('Range')
                    if partial:
                        rg=re.fullmatch(r'bytes=(\d+)-',partial)
                        if not rg or int(rg.group(1))>=size: origin.send_error(416);return
                        offset=int(rg.group(1))
                    origin.send_response(206 if partial else 200)
                    origin.send_header('Content-Type',mime)
                    origin.send_header('Content-Length',str(size-offset))
                    origin.send_header('Cache-Control','no-cache, no-store, max-age=0')
                    origin.send_header('Accept-Ranges','bytes')
                    if partial: origin.send_header('Content-Range',f'bytes {offset}-{size-1}/{size}')
                    if label=='apk':
                        origin.send_header('ETag','"'+p['apk_sha256']+'"')
                        origin.send_header('Content-Disposition','attachment; filename="'+fn+'"')
                    origin.end_headers()
                    if isinstance(obj,bytes): origin.wfile.write(obj[offset:])
                    else:
                        with obj.open('rb') as src:
                            src.seek(offset)
                            while chunk:=src.read(131072):origin.wfile.write(chunk)
                    print(json.dumps(dict(resource=label,status=206 if partial else 200,bytes=size-offset,version=p['version_code'])),flush=True)
            Origin(connection, self.client_address, self.server)
    def do_GET(self): self.send_error(403)
class Server(http.server.ThreadingHTTPServer):
    def handle_error(self,req,client_address):
        print('A fixture connection closed before the test response completed',flush=True)
with Server(('127.0.0.1',args.port),Fixture) as server:
    print(json.dumps(dict(bound=server.server_address,repo=REPO,version=p['version_code'])),flush=True)
    server.serve_forever()
