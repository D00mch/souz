"""Fixed-origin HTTP transport. Only safe GETs are retried."""
import time

from vv_common import Failure, obj, protocol


USER_AGENT = "Python Requests"


class Client:
    def __init__(self, session=None, sleep=time.sleep):
        if session is None:
            import requests
            session = requests.Session()
        self.session = session
        self.sleep = sleep

    def request(self, method, path, **kwargs):
        import requests
        # The provider rejects requests' versioned default User-Agent on guest login.
        # Keep the reference client's header on every step, including before auth.
        kwargs["headers"] = {"User-Agent": USER_AGENT, **kwargs.pop("headers", {})}
        attempts = 3 if method == "GET" else 1
        for attempt in range(attempts):
            try:
                response = self.session.request(
                    method, "https://mobile.vkusvill.ru" + path,
                    timeout=10, allow_redirects=False, **kwargs)
            except requests.RequestException:
                if attempt + 1 < attempts:
                    self.sleep(attempt + 1)
                    continue
                code = "UPSTREAM_UNAVAILABLE" if method == "GET" else "OPERATION_OUTCOME_UNKNOWN"
                raise Failure(code, "Не удалось получить ответ ВкусВилла.", retryable=method == "GET")
            status = response.status_code
            if status >= 500 and attempt + 1 < attempts:
                self.sleep(attempt + 1)
                continue
            if status == 429:
                raw = response.headers.get("Retry-After", "")
                delay = int(raw) if raw.isdigit() and len(raw) < 9 else None
                raise Failure("RATE_LIMITED", "ВкусВилл ограничил частоту запросов.", retry_after=delay)
            if status == 401:
                raise Failure("AUTH_EXPIRED", "ВкусВилл отклонил авторизацию.")
            if status == 403:
                stages = {"/api/guest/createAnonymousCard/": "guest_session_create",
                          "/api/user/": "sms_request", "/api/user/checkSms/": "sms_verify"}
                message = "ВкусВилл вернул HTTP 403. Причина отказа не установлена."
                if path == "/api/guest/createAnonymousCard/":
                    message = "ВкусВилл вернул HTTP 403 при создании гостевой сессии, до отправки телефона и SMS."
                content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                raise Failure("ACCESS_DENIED", message, details={
                    "stage": stages.get(path, "api_request"), "http_status": status,
                    "response_format": "json" if content_type == "application/json" else "non_json",
                })
            if status >= 500:
                code = "UPSTREAM_UNAVAILABLE" if method == "GET" else "OPERATION_OUTCOME_UNKNOWN"
                raise Failure(code, "ВкусВилл временно недоступен.", retryable=method == "GET")
            if status != 200:
                raise protocol()
            try:
                return obj(response.json())
            except (ValueError, TypeError):
                raise protocol()
        raise AssertionError("unreachable")
