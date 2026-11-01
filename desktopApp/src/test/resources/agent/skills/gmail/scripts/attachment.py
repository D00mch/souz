"""Decode Gmail attachment responses saved by NewFile; no network or OAuth tokens."""
import base64
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile


ROOT = Path.home() / 'Documents' / 'souz' / 'gmail-attachments'
MAX_BYTES = 25 * 1024 * 1024


def job_path(value):
    path = Path(value)
    if path.is_symlink() or path.resolve().parent != ROOT.resolve() or not path.is_dir():
        raise ValueError('Invalid attachment directory')
    return path


def clean_response(path):
    for name in ('response.json', 'request.json'):
        (path / name).unlink(missing_ok=True)
    if not any(path.iterdir()):
        path.rmdir()


def run(operation, args):
    if operation == 'prepare':
        message, attachment, filename = args
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,256}', message):
            raise ValueError('Invalid Gmail message ID')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,4096}', attachment):
            raise ValueError('Invalid Gmail attachment ID')
        if not filename or len(filename) > 1024:
            raise ValueError('Invalid attachment filename')
        ROOT.mkdir(parents=True, exist_ok=True)
        path = Path(tempfile.mkdtemp(prefix='download-', dir=ROOT))
        request = path / 'request.json'
        request.write_text(json.dumps({'message': message, 'filename': filename}), encoding='utf-8')
        request.chmod(0o600)
        return str(path)

    path = job_path(args[0])
    if operation == 'check':
        status = json.loads(args[1])
        reconnect = json.loads(args[2])
        if reconnect:
            clean_response(path)
            raise ValueError({'status': 'reconnect_required', 'authorizationUrl': args[3]})
        if status != 200:
            clean_response(path)
            raise ValueError({'status': 'api_error', 'statusCode': status})
        return 'ready'
    if operation != 'decode':
        raise ValueError('Unknown attachment operation')

    try:
        response_path = path / 'response.json'
        if response_path.is_symlink() or response_path.stat().st_size > 4 * ((MAX_BYTES + 2) // 3) + 4096:
            raise ValueError('Attachment response is unsafe or exceeds 25 MiB decoded')
        body = json.loads(response_path.read_text(encoding='utf-8'))
        size, data = body.get('size'), body.get('data')
        if type(size) is not int or not 0 <= size <= MAX_BYTES:
            raise ValueError('Invalid attachment size or exceeds 25 MiB')
        if not isinstance(data, str) or not re.fullmatch(r'[A-Za-z0-9_-]*={0,2}', data):
            raise ValueError('Invalid base64url attachment data')
        content = base64.b64decode(data + '=' * (-len(data) % 4), altchars=b'-_', validate=True)
        if len(content) != size:
            raise ValueError('Attachment size mismatch; response may be truncated')
        request = json.loads((path / 'request.json').read_text(encoding='utf-8'))
        filename = request['filename']
        extension = filename.rsplit('.', 1)[-1].lower() if '.' in filename else ''
        extension = extension if re.fullmatch(r'[a-z0-9]{1,12}', extension) else 'bin'
        if extension == 'pdf' and b'%PDF-' not in content[:1024]:
            raise ValueError('Attachment is not a PDF')
        target = path / ('attachment.' + extension)
        with target.open('xb') as output:
            target.chmod(0o600)
            output.write(content)
        return {'status': 'downloaded', 'filePath': str(target), 'filename': filename,
                'sizeBytes': size, 'sha256': hashlib.sha256(content).hexdigest(), 'untrusted': True}
    finally:
        clean_response(path)


if __name__ == '__main__':
    try:
        result = run(sys.argv[1], sys.argv[2:])
        sys.stdout.write(result if isinstance(result, str) else json.dumps(result, ensure_ascii=False))
    except Exception as error:
        detail = error.args[0] if error.args and isinstance(error.args[0], dict) else {
            'status': 'error', 'message': str(error), 'error': type(error).__name__}
        print(json.dumps(detail, ensure_ascii=False))
        raise SystemExit(1)
