"""Small, secret-free result and validation helpers."""
import datetime
import math


class Failure(Exception):
    def __init__(self, code, message, *, retryable=False, retry_after=None, details=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.retry_after = retry_after
        self.details = details or {}

    def result(self, operation_id=None):
        return {"ok": False, "error": {
            "code": self.code, "message": self.message, "retryable": self.retryable,
            "retry_after_seconds": self.retry_after, "operation_id": operation_id,
            "details": self.details,
        }}


def success(data, warnings=None):
    return {"ok": True, "data": data, "warnings": warnings or []}


def iso(timestamp):
    return datetime.datetime.fromtimestamp(timestamp, datetime.timezone.utc).isoformat()


def protocol():
    return Failure("UPSTREAM_PROTOCOL_ERROR", "Неожиданный формат ответа ВкусВилла.")


def obj(value):
    if not isinstance(value, dict):
        raise protocol()
    return value


def array(value):
    if not isinstance(value, list):
        raise protocol()
    return value


def text(value):
    if not isinstance(value, str) or not value.strip():
        raise protocol()
    return value


def external_id(value):
    if type(value) is int:
        return str(value)
    return text(value)


def quantity(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise protocol()
    return value


def product(product_id, name):
    if type(product_id) is not int or product_id <= 0:
        raise protocol()
    return {"id": product_id, "name": text(name)}
