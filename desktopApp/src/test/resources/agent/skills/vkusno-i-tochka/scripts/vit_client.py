"""Fixed-origin transport; creation is single-attempt, payment pages are never fetched."""
import re
import time
from urllib.parse import urlsplit

from vit_common import Failure, obj, protocol


LOGIN = "/api/v1/user/login/phone"
CONFIRM = "/api/v1/user/login/phone/confirm"
REFRESH = "/api/v1/user/refresh-token"
TOTAL = "/api/v2/order/total"
CREATE = "/api/v2/order/create"
ORDER = "/api/v2/order/get"
PAYMENT_METHODS = "/api/v2/payment/payment_methods"
OFFERS = "/api/v1/order/offers"
ORDER_CONFIG = "/api/v1/order/config"


def actual_offer_path(offer_id):
    if not isinstance(offer_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", offer_id):
        raise Failure("INVALID_ARGUMENT", "Недопустимый ID предложения.")
    return OFFERS + "/" + offer_id + "/actual_offer"
GET_PATHS = {"/api/v1/catalog/cities", "/api/v1/catalog/restaurants",
             "/api/v1/catalog/products", "/api/v2/order/prices", ORDER, PAYMENT_METHODS, OFFERS, ORDER_CONFIG}
POST_PATHS = {LOGIN, CONFIRM, REFRESH, TOTAL, CREATE}


def provider_errors(response, request_body):
    """Bounded error descriptions, with request secrets and arbitrary fields excluded."""
    try:
        if len(getattr(response, "content", b"")) > 65536:
            return []
        data = response.json()
    except (ValueError, TypeError):
        return []
    secrets = []
    secret_keys = {"email", "phone", "code", "ticket", "token", "access_token", "refresh_token",
                   "cardId", "pan", "binding_id", "carPlate"}
    def collect(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in secret_keys and isinstance(item, str) and item:
                    secrets.append(item)
                else:
                    collect(item)
        elif isinstance(value, list):
            for item in value:
                collect(item)
    collect(request_body)
    messages = []
    allowed = {"detail", "message", "error", "errors", "non_field_errors", "reason", "title",
               "description", "msg", "loc", "field", "paymentData", "method", "provider", "email", "token", "cardId",
               "savePaymentMethod", "mailing", "storeId", "wayOfGet", "products", "spProducts",
               "productId", "qty", "type", "productClass", "groupId", "redeemBonuses", "tableNum", "carPlate"}
    def visit(value, path, depth=0):
        if depth > 5 or len(messages) >= 8:
            return
        if isinstance(value, str):
            for secret in sorted(secrets, key=len, reverse=True):
                value = value.replace(secret, "[REDACTED]")
            value = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[EMAIL]", value)
            value = re.sub(r"https?://[^\s\"<>]+", "[URL]", value)
            value = re.sub(r"[A-Za-z0-9_.=-]{32,}", "[OPAQUE]", value)
            value = re.sub(r"(?<!\d)\+?\d[\d ()-]{8,}\d(?!\d)", "[PHONE_OR_NUMBER]", value)
            value = " ".join(value.split())[:300]
            if value:
                messages.append({"field": path or "detail", "message": value})
        elif isinstance(value, list):
            for item in value[:8]:
                visit(item, path, depth + 1)
        elif isinstance(value, dict):
            for key, item in value.items():
                if key in allowed:
                    visit(item, path + "." + key if path else key, depth + 1)
    visit(data, "")
    return messages


class Client:
    def __init__(self, session=None, sleep=time.sleep, proxy_url=None):
        if proxy_url is not None:
            try:
                proxy = urlsplit(proxy_url) if isinstance(proxy_url, str) else None
                if (not proxy or len(proxy_url) > 2048 or any(char.isspace() for char in proxy_url) or
                        proxy.scheme not in ("http", "https") or not proxy.hostname or
                        (proxy.port is not None and not 1 <= proxy.port <= 65535) or
                        proxy.path not in ("", "/") or proxy.query or proxy.fragment):
                    raise ValueError()
            except ValueError:
                raise Failure("PROXY_CONFIGURATION_ERROR", "Проверьте приватную настройку прокси скилла.")
        if session is None:
            import requests
            session = requests.Session()
        self.session, self.sleep = session, sleep
        self.proxy_url = proxy_url

    def request(self, method, path, **kwargs):
        import requests
        if not ((method == "GET" and (path in GET_PATHS or
                re.fullmatch(r"/api/v1/order/offers/[A-Za-z0-9_-]{1,128}/actual_offer", path))) or
                (method == "POST" and path in POST_PATHS)):
            raise Failure("UNSUPPORTED_OPERATION", "Метод не входит в скилл.")
        kwargs["headers"] = {"User-Agent": "okhttp/4.12.0", "Accept": "application/json",
                             **kwargs.pop("headers", {})}
        if self.proxy_url is not None:
            kwargs["proxies"] = {"https": self.proxy_url}
        attempts = 3 if method == "GET" else 1
        stage = {LOGIN: "sms_request", CONFIRM: "sms_verify", REFRESH: "refresh",
                 TOTAL: "calculation", CREATE: "order_creation", ORDER: "order_status",
                 PAYMENT_METHODS: "payment_methods", OFFERS: "offers", ORDER_CONFIG: "offers"}.get(path, "catalog")
        for attempt in range(attempts):
            try:
                response = self.session.request(method, "https://mobile-api.vkusnoitochka.ru" + path,
                                                timeout=25, allow_redirects=False, **kwargs)
            except requests.RequestException:
                if attempt + 1 < attempts:
                    self.sleep(attempt + 1)
                    continue
                safe = method == "GET" or path == TOTAL
                raise Failure("UPSTREAM_UNAVAILABLE" if safe else "OPERATION_OUTCOME_UNKNOWN",
                              "Не удалось получить ответ сервиса.", retryable=safe,
                              details={"stage": stage})
            status = response.status_code
            if status >= 500 and attempt + 1 < attempts:
                self.sleep(attempt + 1)
                continue
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
            details = {"stage": stage, "http_status": status,
                       "response_format": "json" if content_type == "application/json" else "non_json"}
            if status != 200 and content_type == "application/json":
                errors = provider_errors(response, kwargs.get("json"))
                if errors:
                    details["provider_errors"] = errors
            if status == 429:
                raw = response.headers.get("Retry-After", "")
                delay = int(raw) if raw.isdigit() and len(raw) < 9 else None
                raise Failure("RATE_LIMITED", "Сервис ограничил частоту запросов.",
                              retry_after=delay, details=details)
            if status == 401:
                raise Failure("AUTH_EXPIRED", "Сервис отклонил авторизацию.", details=details)
            if status == 403:
                # The API also reports expired access as JSON 403, not just 401.
                try:
                    error = response.json() if content_type == "application/json" else None
                except (ValueError, TypeError):
                    error = None
                detail = error.get("detail") if isinstance(error, dict) else None
                if isinstance(detail, str) and detail.strip().casefold().replace("ё", "е") == "доступ истек":
                    raise Failure("AUTH_EXPIRED", "Срок доступа истёк; требуется обновление сессии.",
                                  details={**details, "provider_reason": "access_expired"})
                raise Failure("ACCESS_DENIED", "Сервис вернул HTTP 403; причина не установлена.",
                              details=details)
            if status >= 500:
                safe = method == "GET" or path == TOTAL
                raise Failure("UPSTREAM_UNAVAILABLE" if safe else "OPERATION_OUTCOME_UNKNOWN",
                              "Сервис временно недоступен.", retryable=safe, details=details)
            if status != 200:
                if path == CREATE and not (400 <= status < 500 and status != 408):
                    raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход создания заказа неизвестен.", details=details)
                if path == CREATE and status == 408:
                    raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход создания заказа неизвестен.", details=details)
                raise Failure("REQUEST_REJECTED", "Сервис отклонил запрос.", details=details)
            try:
                return obj(response.json())
            except (ValueError, TypeError):
                raise protocol()
