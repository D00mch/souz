"""Parsed, secret-free results shared by the skill's scripts."""
import datetime
import math


class Failure(Exception):
    def __init__(self, code, message, *, retryable=False, retry_after=None, details=None):
        super().__init__(message)
        self.code, self.message = code, message
        self.retryable, self.retry_after = retryable, retry_after
        self.details = details or {}

    def result(self, operation_id=None):
        return {"ok": False, "error": {
            "code": self.code, "message": self.message, "retryable": self.retryable,
            "retry_after_seconds": self.retry_after, "operation_id": operation_id,
            "details": self.details}}


def success(data, warnings=None):
    return {"ok": True, "data": data, "warnings": warnings or []}


def protocol():
    return Failure("UPSTREAM_PROTOCOL_ERROR", "Неожиданный формат ответа сервиса.")


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


def number(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise protocol()
    return value


def iso(value):
    return datetime.datetime.fromtimestamp(value, datetime.timezone.utc).isoformat()
