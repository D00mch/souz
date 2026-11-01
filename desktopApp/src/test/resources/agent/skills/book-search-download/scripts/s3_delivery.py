"""Standard-library S3 SigV4 delivery; never log credentials or signed URLs."""
import sys
sys.dont_write_bytecode = True
import argparse
import datetime as dt
import hashlib
import hmac
import json
import mimetypes
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse as u
import urllib.request as r
import uuid

MAX_BYTES = 20 * 1024 * 1024
DEFAULT_CONFIG = '/souz/state/librusec-books/s3.json'

class NoRedirect(r.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def expiry(value):
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 604800:
        raise ValueError('Expiry must be an integer from 1 to 604800 seconds')
    return value

def q(value):
    return u.quote(str(value), safe='-_.~')

class S3:
    def __init__(self, config):
        self.c = config
        ep = u.urlsplit(config['endpoint_url'])
        if ep.scheme != 'https' or not ep.hostname or ep.username or ep.password or ep.query or ep.fragment or ep.path not in ('', '/'):
            raise ValueError('Endpoint must be a root HTTPS URL')
        if config['addressing_style'] != 'path' or config['signature_version'] != 's3v4':
            raise ValueError('Only path style and s3v4 supported')
        if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', config['bucket']):
            raise ValueError('Invalid bucket')
        if not re.fullmatch(r'[a-z0-9-]+', config['region_name']):
            raise ValueError('Invalid region')
        for field in ('aws_access_key_id', 'aws_secret_access_key'):
            if not isinstance(config[field], str) or not config[field] or any(ord(ch)<32 for ch in config[field]):
                raise ValueError('Invalid credential configuration')
        self.expiry = expiry(config.get('presigned_url_expiry_seconds', 3600))
        self.host = ep.netloc
        self.base = 'https://' + ep.netloc
        self.opener = r.build_opener(NoRedirect())

    def path(self, key):
        if not isinstance(key, str) or not key or len(key.encode('utf-8')) > 1024 or any(ord(ch)<32 for ch in key):
            raise ValueError('Invalid object key')
        return '/' + q(self.c['bucket']) + '/' + u.quote(key, safe='/-_.~')

    def sign(self, method, path, query, headers, payload_hash, now):
        stamp = now.strftime('%Y%m%dT%H%M%SZ'); day = stamp[:8]
        scope = day + '/' + self.c['region_name'] + '/s3/aws4_request'
        names = ';'.join(sorted(headers))
        canonical_headers = ''.join(k + ':' + ' '.join(headers[k].split()) + '\n' for k in sorted(headers))
        canonical = '\n'.join((method, path, query, canonical_headers, names, payload_hash))
        string = '\n'.join(('AWS4-HMAC-SHA256', stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()))
        key = ('AWS4' + self.c['aws_secret_access_key']).encode()
        for part in (day, self.c['region_name'], 's3', 'aws4_request'):
            key = hmac.new(key, part.encode(), hashlib.sha256).digest()
        return hmac.new(key, string.encode(), hashlib.sha256).hexdigest(), scope, names

    def request(self, method, key, data=None, extra=None):
        path = self.path(key)
        now = dt.datetime.now(dt.timezone.utc)
        digest = hashlib.sha256(data if data is not None else b'').hexdigest()
        headers = {'host': self.host, 'x-amz-date': now.strftime('%Y%m%dT%H%M%SZ'), 'x-amz-content-sha256': digest}
        headers.update(extra or {})
        signature, scope, names = self.sign(method, path, '', headers, digest, now)
        headers['authorization'] = ('AWS4-HMAC-SHA256 Credential=' + self.c['aws_access_key_id'] + '/' + scope + ', SignedHeaders=' + names + ', Signature=' + signature)
        req = r.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=20) as response:
                return response.status, dict(response.headers)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            return status, {}
        except Exception:
            raise RuntimeError('S3 transport failed (details suppressed)') from None

    def presign(self, key, seconds=None):
        seconds = self.expiry if seconds is None else expiry(seconds)
        now = dt.datetime.now(dt.timezone.utc)
        scope = now.strftime('%Y%m%d') + '/' + self.c['region_name'] + '/s3/aws4_request'
        params = {'X-Amz-Algorithm': 'AWS4-HMAC-SHA256', 'X-Amz-Credential': self.c['aws_access_key_id'] + '/' + scope, 'X-Amz-Date': now.strftime('%Y%m%dT%H%M%SZ'), 'X-Amz-Expires': str(seconds), 'X-Amz-SignedHeaders': 'host'}
        query = '&'.join(q(k) + '=' + q(v) for k,v in sorted(params.items()))
        signature, _, _ = self.sign('GET', self.path(key), query, {'host': self.host}, 'UNSIGNED-PAYLOAD', now)
        return self.base + self.path(key) + '?' + query + '&X-Amz-Signature=' + signature

def content_headers(filename, content_type=None):
    name = Path(filename).name
    fallback = ''.join(ch if ch.isascii() and ch.isalnum() or ch in '.-_' else '_' for ch in name) or 'download'
    ctype = content_type or mimetypes.guess_type(name)[0] or 'application/octet-stream'
    if not re.fullmatch(r'[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:; charset=utf-8)?', ctype):
        raise ValueError('Invalid content type')
    return {'content-type': ctype, 'content-disposition': 'attachment; filename="' + fallback + '"; filename*=UTF-8\'\'' + q(name)}

def manifest_path(value):
    path = Path(value).absolute()
    bundle = Path(__file__).resolve().parents[1]
    resolved = path.resolve()
    if resolved == bundle or bundle in resolved.parents:
        raise ValueError('Manifest must be outside bundle')
    if not path.parent.is_dir():
        raise ValueError('Manifest parent must exist')
    return path

def write_manifest(path, obj, exclusive=False):
    flags = os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW | (os.O_EXCL if exclusive else os.O_TRUNC)
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(obj, output, indent=2)
        output.write('\n')

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', default=DEFAULT_CONFIG)
    sub = p.add_subparsers(dest='action', required=True)
    upload = sub.add_parser('upload')
    upload.add_argument('--file', required=True)
    upload.add_argument('--manifest', required=True)
    upload.add_argument('--content-type')
    upload.add_argument('--expiry', type=int)
    for action in ('delete', 'head'):
        command = sub.add_parser(action)
        group = command.add_mutually_exclusive_group(required=True)
        group.add_argument('--key'); group.add_argument('--manifest')
    a = p.parse_args(argv)
    client = S3(json.loads(Path(a.config).read_text(encoding='utf-8')))
    if a.action == 'upload':
        seconds = client.expiry if a.expiry is None else expiry(a.expiry)
        src = Path(a.file)
        if not src.is_file(): raise ValueError('Input must be a regular file')
        with src.open('rb') as stream: data = stream.read(MAX_BYTES + 1)
        if not data or len(data) > MAX_BYTES: raise ValueError('File must be nonempty and at most 20 MiB')
        headers = content_headers(src.name, a.content_type)
        path = manifest_path(a.manifest)
        key = 'delivery/' + uuid.uuid4().hex + '/' + src.name
        client.path(key)
        record = {'bucket': client.c['bucket'], 'key': key, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'content_type': headers['content-type'], 'content_disposition': headers['content-disposition'], 'expiry_seconds': seconds, 'uploaded': False, 'sent': False}
        write_manifest(path, record, exclusive=True)
        status, _ = client.request('PUT', key, data, headers)
        if not 200 <= status < 300: raise RuntimeError('S3 PUT failed with HTTP ' + str(status))
        record.update(uploaded=True, presigned_url=client.presign(key, seconds), created_at=dt.datetime.now(dt.timezone.utc).isoformat())
        write_manifest(path, record)
        print(json.dumps({'manifest': str(path), 'bytes': len(data), 'uploaded': True, 'sent': False}))
    else:
        key = a.key
        if a.manifest:
            record = json.loads(manifest_path(a.manifest).read_text())
            if record['bucket'] != client.c['bucket']: raise ValueError('Manifest bucket mismatch')
            key = record['key']
        status, _ = client.request(a.action.upper(), key)
        if a.action == 'delete' and not (200 <= status < 300 or status == 404):
            raise RuntimeError('S3 DELETE failed with HTTP ' + str(status))
        if a.action == 'head' and not (status == 200 or status == 404):
            raise RuntimeError('S3 HEAD failed with HTTP ' + str(status))
        print(json.dumps({'operation': a.action, 'status': status, 'exists': status == 200 if a.action == 'head' else None}))

if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Exceptions can include bearer URLs/credentials: never stringify them.
        print('S3 delivery failed; details suppressed. Check config, input, or private manifest; use delete to clean up a pending upload.', file=sys.stderr)
        sys.exit(1)
