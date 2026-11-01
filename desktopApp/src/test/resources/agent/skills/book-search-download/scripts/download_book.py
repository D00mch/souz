"""Authorized Librusec search/download; no uploading or sending. Requires requests."""
import sys
sys.dont_write_bytecode = True
import argparse, hashlib, io, json, os, re, sys, time, unicodedata, zipfile
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit
import xml.etree.ElementTree as ET
import requests
from http_state import HttpState, StateError, action_for
BASE='https://librusec.bot'
MAX=20_000_000
class Failure(Exception): pass
class Page(HTMLParser):
    def __init__(self,text):
        super().__init__(); self.links=[];self.forms=[];self.inputs={};self.paragraphs=[];self.stack=[];self.p=None;self.form=None
        self.feed(text)
    def handle_starttag(self,tag,attrs):
        d=dict(attrs)
        if tag=='p': self.p={'text':[], 'books':[]}
        if tag=='a':
            item={'href':d.get('href',''),'text':[]}; self.links.append(item);self.stack.append(item)
            if self.p is not None and item['href'].startswith('/book/'): self.p['books'].append(item)
        if tag=='form':
            self.form={'action':d.get('action',''),'method':d.get('method','get').lower(),'text':[]};self.forms.append(self.form)
        if tag=='input' and 'name' in d: self.inputs[d['name']]=d.get('value','')
    def handle_data(self,data):
        if self.stack: self.stack[-1]['text'].append(data)
        if self.p is not None: self.p['text'].append(data)
        if self.form is not None: self.form['text'].append(data)
    def handle_endtag(self,tag):
        if tag=='form': self.form=None
        if tag=='a' and self.stack: self.stack.pop()
        if tag=='p' and self.p is not None: self.paragraphs.append(self.p);self.p=None

def norm(t): return ' '.join(unicodedata.normalize('NFKC',t).casefold().replace('ё','е').split())
def safe_url(path):
    u=urljoin(BASE,path);p=urlsplit(u)
    if p.scheme!='https' or p.netloc!='librusec.bot' or p.username or p.fragment: raise Failure('Refused non-site URL')
    return u
class Client:
    def __init__(self):
        self.s=requests.Session();self.state=None;self.proxy_url=None;self.proxy_route='direct'
    def close(self):
        try: self.s.close()
        finally:
            if self.state: self.state.close()
    def save_state(self):
        if self.state: self.state.save()
    def request(self,method,path,**kwargs):
        u=safe_url(path)
        for _ in range(5):
            action=action_for(u)
            use_proxy=self.proxy_url is not None and urlsplit(u).path in ('/entry','/auth/new_book_user')
            if self.proxy_url is not None:
                kwargs['proxies']={'https':self.proxy_url} if use_proxy else {}
            if self.state:
                self.state.route_id=self.proxy_route if use_proxy else 'direct'
            if self.state:
                try:self.state.before_request(action)
                except StateError as e:raise Failure(str(e)) from None
            with self.s.request(method,u,timeout=(15,45),allow_redirects=False,stream=True,**kwargs) as r:
                self.save_state()
                if r.status_code==429:
                    body=r.raw.read(4096,decode_content=True).decode('utf-8',errors='replace')
                    message=self.state.rate_limited(action,r.headers,body) if self.state else 'HTTP 429 at '+action+'; stopped without retry'
                    raise Failure(message)
                if r.status_code in (301,302,303,307,308):
                    u=safe_url(r.headers.get('Location',''))
                    if r.status_code in (301,302,303): method='GET';kwargs.pop('data',None)
                    continue
                if r.status_code!=200: raise Failure('HTTP '+str(r.status_code)+'; stopped without retry/bypass')
                data=bytearray()
                for chunk in r.iter_content(65536):
                    data.extend(chunk)
                    if len(data)>MAX: raise Failure('Response exceeds 20 MB')
                return bytes(data)
        raise Failure('Too many redirects')
    def html(self,method,path,**kw):
        text=self.request(method,path,**kw).decode('utf-8')
        if getattr(self,'authenticated',False) and any(f['action']=='/entry' for f in Page(text).forms):
            raise Failure('Session expired; login required')
        return text
    def configure_network(self,path):
        self.proxy_url=None;self.proxy_route='direct';self.s.proxies={}
        config=Path(path).resolve().parent/'network.json'
        if not config.exists(): return 'direct'
        try:
            value=json.loads(config.read_text())['auth_proxy']
            if not isinstance(value,str) or len(value)>2048 or any(ch.isspace() for ch in value): raise ValueError()
            proxy=urlsplit(value)
            if proxy.scheme not in ('http','https') or not proxy.hostname or proxy.path not in ('','/') or proxy.query or proxy.fragment: raise ValueError()
            port=proxy.port or (443 if proxy.scheme=='https' else 80)
            if not 1<=port<=65535: raise ValueError()
        except Exception: raise Failure('Invalid private proxy configuration; no direct fallback') from None
        self.s.trust_env=False
        self.proxy_url=value
        endpoint=proxy.scheme+'://'+proxy.hostname+':'+str(port)
        self.proxy_route='proxy-'+hashlib.sha256(endpoint.encode()).hexdigest()[:16]
        return self.proxy_route
    def login(self,path):
        route_id=self.configure_network(path)
        credentials=json.loads(Path(path).read_text())
        try:self.state=HttpState(path,credentials['login'],route_id=route_id)
        except StateError as e:raise Failure(str(e)) from None
        self.s.cookies=self.state.cookies
        def confirmed(text):
            return credentials['login'] in text and not any(f['action']=='/entry' for f in Page(text).forms)
        try:
            if any(True for _ in self.s.cookies):
                text=self.html('GET','/')
                if confirmed(text):
                    self.authenticated=True;return
                self.s.cookies.clear();self.save_state()
            text=self.html('POST','/entry',data={'username':credentials['login'],'password':credentials['password']})
            if not confirmed(text): raise Failure('Login not confirmed; check access privately')
            self.authenticated=True
            self.save_state()
        except Exception:
            self.close();raise

def candidates(text):
    out=[]
    for p in Page(text).paragraphs:
        for b in p['books']: out.append({'url':safe_url(b['href']),'title':' '.join(''.join(b['text']).split()),'evidence':' '.join(''.join(p['text']).split())})
    return out

def validate(data,fmt,title,author):
    if not data: raise Failure('Empty download')
    if fmt=='txt':
        text=data.decode('utf-8-sig')
        if text.lstrip().lower().startswith(('<html','<!doctype html')) or len(text.strip())<100: raise Failure('Not usable book text')
        if norm(title) not in norm(text[:10000]) or not all(norm(x) in norm(text[:10000]) for x in author.split()): raise Failure('TXT title/author mismatch')
        return {'characters':len(text),'metadata_verified':True}
    markup=data.replace(b'\x00',b'').upper()
    if b'<!DOCTYPE' in markup or b'<!ENTITY' in markup: raise Failure('DTD/entities rejected')
    root=ET.fromstring(data)
    if root.tag.split('}')[-1]!='FictionBook': raise Failure('Not FB2 XML (ZIP is not supported)')
    ns={'f':'http://www.gribuser.ru/xml/fictionbook/2.0'}
    info=root.find('f:description/f:title-info',ns)
    if info is None: raise Failure('Missing FB2 title-info')
    actual=info.findtext('f:book-title',default='',namespaces=ns)
    authors=[' '.join(n.findtext('f:'+part,default='',namespaces=ns).strip() for part in ('first-name','middle-name','last-name')).strip() for n in info.findall('f:author',ns)]
    from catalog import contains_words
    expected_authors=[a.strip() for a in author.split(',') if a.strip()]
    if norm(actual)!=norm(title) or not all(any(contains_words(actual_author, expected) for actual_author in authors) for expected in expected_authors): raise Failure('FB2 title/author mismatch')
    bodies=root.findall('f:body',ns); text=' '.join(' '.join(b.itertext()) for b in bodies)
    if len(text.strip())<100: raise Failure('Empty/truncated FB2 body')
    return {'xml_title':actual,'xml_authors':authors,'characters':len(text),'bodies':len(bodies),'sections':sum(len(b.findall('.//f:section',ns)) for b in bodies),'metadata_verified':True}

def unpack_fb2(data):
    if not data.startswith(b'PK'): return data
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries=[n for n in archive.infolist() if not n.is_dir()]
        if len(entries)!=1 or not entries[0].filename.lower().endswith('.fb2'):
            raise Failure('Expected a ZIP containing one FB2')
        if entries[0].file_size>MAX: raise Failure('Unpacked FB2 exceeds 20 MB')
        with archive.open(entries[0]) as source:
            unpacked=source.read(MAX+1)
        if len(unpacked)>MAX: raise Failure('Unpacked FB2 exceeds 20 MB')
        return unpacked

def filename_for(author,title,fmt):
    stem=re.sub(r'[\x00-\x1f/\\:*?"<>|]', '_', author+' — '+title)
    stem=stem.encode('utf-8')[:230].decode('utf-8',errors='ignore').strip(' .') or 'book'
    return stem+'.'+fmt

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--credentials',default='/souz/state/librusec-books/credentials.json')
    sub=p.add_subparsers(dest='action',required=True)
    s=sub.add_parser('search');s.add_argument('--query',required=True);s.add_argument('--page',type=int,default=0)
    d=sub.add_parser('download');d.add_argument('--query',default='');d.add_argument('--page',type=int,default=0);d.add_argument('--title');d.add_argument('--author');d.add_argument('--book-url');d.add_argument('--format',choices=['auto','fb2','txt'],default='auto');d.add_argument('--rights',required=True,choices=['public-domain','permission']);d.add_argument('--output-dir',required=True)
    a=p.parse_args()
    if not a.query.strip() and (a.action=='search' or not a.book_url): raise Failure('Supply query or observed book URL')
    if a.page<0: raise Failure('Invalid page')
    out=None
    if a.action=='download':
        out=Path(a.output_dir)
        if not out.is_absolute() or out.is_symlink(): raise Failure('Use absolute private output directory')
        out=out.resolve(); bundle=Path(__file__).resolve().parents[1]
        if out==bundle or bundle in out.parents or (out.exists() and any(out.iterdir())): raise Failure('Output must be empty and outside bundle')
    c=Client()
    try:
        c.login(a.credentials)
        from catalog import search, details, contains_words
        if a.action=='search':
            print(json.dumps(search(c,a.query,a.page),ensure_ascii=False));return
        if a.book_url:
            selected=a.book_url
        else:
            if not a.title or not a.author: raise Failure('Supply title and author, or an observed book URL')
            hits=search(c,a.query,a.page,author=a.author,title=a.title)['results']
            matches=[h for h in hits if norm(h['title'])==norm(a.title)]
            if len(matches)!=1: raise Failure('Multiple or absent editions; inspect catalogue and select observed book URL')
            selected=matches[0]['url']
        info,controls=details(c,selected)
        if a.title and norm(info['title'])!=norm(a.title): raise Failure('Book-page title mismatch')
        if a.author and not contains_words(info['author'],a.author): raise Failure('Book-page author mismatch')
        a.title,a.author=info['title'],info['author']
        hit={'url':info['url']}
        fmt=a.format
        if fmt=='auto': fmt='fb2' if 'fb2' in controls else 'txt'
        if fmt not in controls: raise Failure('Requested format not offered by the book page')
        entry=controls[fmt]
        wait=c.html('GET',entry)
        poll=re.search(r'fetch\(["\'](/read/cdl/[a-zA-Z0-9]+)["\']\)',wait)
        # Derive final URL template from actual waiting-page Javascript; never invent tokens.
        final=re.search(r'window\.location\.href\s*=\s*["\'](/read/dl/)["\']\s*\+\s*result\.ek2\s*\+\s*["\']([^"\']*)["\']',wait)
        if not poll or not final: raise Failure('Waiting-page protocol changed or access challenge')
        deadline=time.monotonic()+120
        while True:
            status=json.loads(c.request('GET',poll[1]))
            if status.get('is_completed'): break
            if time.monotonic()>deadline: raise Failure('Download preparation timed out')
            time.sleep(2)
        if status.get('state')!=0 or not re.fullmatch('[A-Za-z0-9]+',str(status.get('ek2',''))): raise Failure('Download preparation failed')
        data=c.request('GET',final[1]+status['ek2']+final[2])
        if fmt=='fb2': data=unpack_fb2(data)
        validation=validate(data,fmt,a.title,a.author)
        out.mkdir(parents=True,exist_ok=True,mode=0o700);os.chmod(out,0o700)
        filename=filename_for(a.author,a.title,fmt)
        file=out/filename
        with file.open('xb') as f: os.chmod(file,0o600);f.write(data)
        record={'file':str(file),'format':fmt,'title':a.title,'author':a.author,'source_url':hit['url'],'rights_assertion':a.rights,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'validation':validation,'sent':False}
        manifest=out/'download.json'
        with manifest.open('x',encoding='utf-8') as f: os.chmod(manifest,0o600);json.dump(record,f,ensure_ascii=False,indent=2)
        print(json.dumps(record,ensure_ascii=False))
    finally: c.close()

if __name__=='__main__':
    sys.modules['download_book']=sys.modules[__name__]
    try: main()
    except Exception as e:
        # Never print remote bodies, credentials, cookies or exception URLs.
        print('Download/search failed: '+(str(e) if isinstance(e,Failure) else type(e).__name__),file=sys.stderr);sys.exit(1)
