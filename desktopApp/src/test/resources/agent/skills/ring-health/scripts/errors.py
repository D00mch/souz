"""Bounded public HTTP errors; API response bodies are never forwarded."""
import json
import sys


def http_error(status):
    code, message = {
        400: ('BAD_REQUEST', 'API кольца отклонил запрос. Это не подтверждает, что токен неверный.'),
        401: ('AUTH_INVALID', 'API кольца не принял токен. Установите актуальный личный токен.'),
        403: ('ACCESS_DENIED', 'API кольца отказал в доступе. Проверьте права токена и доступ к данным кольца.'),
        404: ('NOT_FOUND', 'API кольца не нашёл запрошенный ресурс.'),
        408: ('REQUEST_TIMEOUT', 'API кольца не успел обработать запрос. Повторите позже.'),
        422: ('INVALID_REQUEST', 'API кольца не принял параметры запроса.'),
        429: ('RATE_LIMITED', 'Слишком много запросов к API кольца. Повторите позже.'),
    }.get(status, ('CLIENT_ERROR', 'API кольца отклонил запрос.') if 400 <= status < 500
          else ('SERVICE_UNAVAILABLE', 'API кольца временно недоступен.') if status >= 500
          else ('UNEXPECTED_HTTP_STATUS', 'API кольца вернул неожиданный HTTP-статус.'))
    return {'code': code, 'httpStatus': status, 'message': message,
            'retryable': status in (408, 429) or status >= 500}


class RingHttpError(ValueError):
    def __init__(self, status):
        self.details = http_error(status)
        super().__init__(self.details['code'].lower())


if __name__ == '__main__':
    print(json.dumps({'ok': False, 'error': http_error(int(sys.argv[1]))}, ensure_ascii=False))
