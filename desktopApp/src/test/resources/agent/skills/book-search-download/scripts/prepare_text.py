import sys
sys.dont_write_bytecode = True
import argparse,json,sys,hashlib,os
from pathlib import Path
import xml.etree.ElementTree as ET

def extract(data, fmt):
    if fmt == 'txt':
        return data.decode('utf-8-sig')
    markup=data.replace(b'\x00',b'').upper()
    if b'<!DOCTYPE' in markup or b'<!ENTITY' in markup:
        raise ValueError('DTD/entities are not supported')
    root=ET.fromstring(data)
    if root.tag.split('}')[-1] != 'FictionBook':
        raise ValueError('Expected FictionBook document')
    paragraphs=[]
    for body in root:
        if body.tag.split('}')[-1] == 'body':
            for node in body.iter():
                if node.tag.split('}')[-1] in ('p','subtitle','text-author','v','date'):
                    line=''.join(node.itertext()).strip()
                    if line: paragraphs.append(line)
    return '\n\n'.join(paragraphs)

def chunks(text, limit):
    parts=[]
    while text:
        if len(text)<=limit:
            parts.append(text);break
        cut=text.rfind('\n',0,limit+1)
        if cut<limit//2: cut=text.rfind(' ',0,limit+1)
        if cut<limit//2: cut=limit
        elif cut<limit: cut+=1
        parts.append(text[:cut]);text=text[cut:]
    return parts

def main():
    p=argparse.ArgumentParser(description='Prepare local legal TXT/FB2 for a caller channel skill. Never sends.')
    p.add_argument('--input',required=True);p.add_argument('--format',choices=['txt','fb2'],required=True)
    p.add_argument('--title',required=True);p.add_argument('--author',required=True)
    p.add_argument('--source-url',required=True);p.add_argument('--rights',choices=['public-domain','permission'],required=True)
    p.add_argument('--limit',type=int,required=True,help='Actual channel plain-text character limit, after reserving room for headers')
    p.add_argument('--output-dir',required=True)
    a=p.parse_args()
    if a.limit<1: p.error('limit must be positive')
    src=Path(a.input).resolve();out=Path(a.output_dir).resolve()
    bundle=Path(__file__).resolve().parents[1]
    if out==bundle or bundle in out.parents: p.error('outputs must stay outside bundle')
    with src.open('rb') as stream: data=stream.read(20*1024*1024+1)
    if len(data)>20*1024*1024: raise ValueError('input larger than 20 MiB')
    text=extract(data,a.format).replace('\r\n','\n').replace('\r','\n')
    if not text.strip(): raise ValueError('empty book text')
    if text.lstrip().lower().startswith(('<!doctype html','<html')): raise ValueError('HTML/login page is not book text')
    if out.exists() and any(out.iterdir()): raise ValueError('output directory must be empty')
    out.mkdir(parents=True,exist_ok=True,mode=0o700)
    os.chmod(out,0o700)
    os.umask(0o077)
    parts=chunks(text,a.limit)
    (out/'book.txt').write_text(text,encoding='utf-8')
    manifest={'title':a.title,'author':a.author,'source_url':a.source_url,'rights_assertion':a.rights,'sha256':hashlib.sha256(data).hexdigest(),'characters':len(text),'limit':a.limit,'sent':False,'chunks':[]}
    for i,part in enumerate(parts,1):
        name=f'part-{i:04d}.txt';(out/name).write_text(part,encoding='utf-8')
        manifest['chunks'].append({'file':name,'characters':len(part)})
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'manifest':str(out/'manifest.json'),'chunks':len(parts),'sent':False},ensure_ascii=False))
if __name__=='__main__':
    try: main()
    except (ValueError,OSError,UnicodeError,ET.ParseError) as e:
        print('Preparation failed: '+str(e),file=sys.stderr);sys.exit(1)
