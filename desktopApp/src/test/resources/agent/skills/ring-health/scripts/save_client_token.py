"""Consume an internal composite result and expose only a safe credential status."""
import json
import sys

sys.dont_write_bytecode = True
from auth import configured, install_token, token_path


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate field')
        result[key] = value
    return result


def main():
    try:
        path = token_path()
        preserved = configured(path)
        if len(sys.argv) != 2 or len(sys.argv[1]) > 16384:
            raise ValueError('invalid result size')
        data = json.loads(sys.argv[1], object_pairs_hook=unique_object)
        if not isinstance(data, dict):
            raise ValueError('invalid result type')
        if data.get('status') in ('failed', 'cancelled', 'timed_out'):
            code, message = {
                'cancelled': ('TOKEN_REQUEST_CANCELLED', 'Ввод токена отменён.'),
                'timed_out': ('TOKEN_REQUEST_TIMEOUT', 'Время ожидания токена истекло.'),
                'failed': ('TOKEN_REQUEST_FAILED', 'Клиент не смог предоставить токен.'),
            }[data['status']]
            result = {'ok': False, 'error': {'code': code, 'message': message, 'retryable': False},
                      'previousTokenPreserved': preserved}
            exit_code = 1
        else:
            if set(data) != {'token'} or not isinstance(data['token'], str):
                raise ValueError('invalid token result')
            exit_code, result = install_token(path, data['token'].strip())
    except (OSError, ValueError, UnicodeError):
        result = {'ok': False, 'error': {'code': 'TOKEN_RESULT_INVALID',
                  'message': 'Не удалось обработать ответ клиента с токеном.', 'retryable': False}}
        exit_code = 1
    print(json.dumps(result, ensure_ascii=False))
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
