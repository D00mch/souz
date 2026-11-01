"""User-scoped ring credentials, kept outside the immutable skill bundle."""
import json
import os
from pathlib import Path
import sys
import tempfile
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
from errors import http_error
MAX_TOKEN_BYTES = 4096


def token_path():
    root = Path(__file__).resolve().parent.parent
    if root.parent.name == 'bundles':
        root = root.parent.parent
    if root.name != 'ring-health':
        raise ValueError('invalid skill location')
    directory = root.parent / '.ring-health-state'
    if directory.is_symlink():
        raise ValueError('invalid state directory')
    return directory / 'token'


def valid_token(value):
    return bool(value) and len(value) <= MAX_TOKEN_BYTES and all(33 <= ord(c) <= 126 for c in value)


def read_token(path):
    if path.is_symlink():
        raise ValueError('invalid token file')
    if not path.exists():
        return None
    with path.open(encoding='ascii') as handle:
        value = handle.read(MAX_TOKEN_BYTES + 2).rstrip('\n')
    return value if valid_token(value) else None


def configured(path):
    return read_token(path) is not None


def verify_token(value):
    request = urllib.request.Request(
        'https://app.life-balance.tech/external/sync/sleep?page=0&pageSize=1',
        headers={'Authorization': 'Bearer ' + value})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return None if 200 <= response.status < 300 else http_error(response.status)
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        return http_error(status)
    except (urllib.error.URLError, TimeoutError, OSError):
        return {'code': 'NETWORK_ERROR', 'message': 'Не удалось связаться с API кольца. Повторите позже.', 'retryable': True}


def save_token(path, value):
    path.parent.mkdir(mode=0o700, parents=False, exist_ok=True)
    path.parent.chmod(0o700)
    fd, temporary = tempfile.mkstemp(prefix='.token-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='ascii') as handle:
            handle.write(value + '\n')
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def install_token(path, value):
    previous = read_token(path)
    if not valid_token(value):
        return 2, {'ok': False, 'error': {'code': 'INVALID_TOKEN',
                          'message': 'Токен должен содержать 1–4096 печатных ASCII-символов без пробелов.',
                          'retryable': False}, 'tokenConfigured': previous is not None,
                          'previousTokenPreserved': previous is not None}
    if previous == value:
        return 0, {'ok': True, 'tokenConfigured': True, 'action': 'unchanged'}
    error = verify_token(value)
    if error:
        return 1, {'ok': False, 'error': error, 'tokenConfigured': previous is not None,
                          'previousTokenPreserved': previous is not None}
    save_token(path, value)
    return 0, {'ok': True, 'tokenConfigured': True, 'verified': True,
                      'action': 'updated' if previous is not None else 'installed'}


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ('auth-status', 'set-token', 'token-path'):
        print('Error: expected auth-status or set-token', file=sys.stderr)
        return 2
    try:
        path = token_path()
        if sys.argv[1] == 'set-token':
            value = sys.stdin.read(MAX_TOKEN_BYTES + 2).strip()
            code, result = install_token(path, value)
            print(json.dumps(result, ensure_ascii=False))
            return code
        elif sys.argv[1] == 'auth-status':
            print(json.dumps({'ok': True, 'tokenConfigured': configured(path)}))
        elif configured(path):
            print(path)
        else:
            print('Error: AUTH_REQUIRED: запросите личный токен через композит connect и поле ввода клиента.', file=sys.stderr)
            return 1
    except (OSError, ValueError, UnicodeError):
        print('Error: ring credential storage unavailable', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
