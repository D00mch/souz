"""Souz entrypoint: one method in argv, one JSON object on stdin/stdout."""
import json
import re
import sqlite3
import sys

# Installed bundles are immutable, including when invoked without Docker's env default.
sys.dont_write_bytecode = True

from vv_auth import Auth
from vv_client import Client
from vv_common import Failure, success
from vv_shop import Shop
from vv_storage import Store, state_directory


CART_METHODS = {"add_to_cart", "remove_from_cart", "clear_cart"}
JOURNALED = CART_METHODS | {"start_phone_auth", "submit_sms_code"}
FIELDS = {
    "get_auth_status": (set(), set()),
    "start_phone_auth": ({"phone", "request_id"}, set()),
    "submit_sms_code": ({"auth_session_id", "code", "request_id"}, set()),
    "resend_sms_code": ({"auth_session_id", "request_id"}, set()),
    "cancel_phone_auth": ({"auth_session_id"}, set()),
    "logout": (set(), set()),
    "search_products": ({"query"}, set()),
    "get_product_analogs": ({"product_id"}, set()),
    "get_completed_orders": (set(), {"limit"}),
    "get_order_details": ({"id_order"}, set()),
    "get_cart": (set(), set()),
    "add_to_cart": ({"product_ids", "request_id"}, {"substitution_policy"}),
    "remove_from_cart": ({"product_ids", "request_id"}, set()),
    "clear_cart": ({"request_id"}, set()),
}


def validate(method, arguments):
    if method not in FIELDS:
        raise Failure("UNSUPPORTED_OPERATION", "Метод не входит в этот пакет ВкусВилла.")
    required, optional = FIELDS[method]
    if not isinstance(arguments, dict) or not required <= arguments.keys() or arguments.keys() - required - optional:
        raise Failure("INVALID_ARGUMENT", "Проверьте обязательные и неизвестные поля метода.")
    args = dict(arguments)
    for key in ("phone", "code", "auth_session_id", "request_id", "query", "id_order"):
        if key in args:
            value = args[key]
            if not isinstance(value, str) or not value.strip() or len(value) > (500 if key == "query" else 128):
                raise Failure("INVALID_ARGUMENT", "Ожидается непустая строка допустимой длины: " + key)
            args[key] = value.strip()
    if "phone" in args:
        phone = args["phone"]
        if not re.fullmatch(r"\+?[0-9 ()-]+", phone):
            raise Failure("INVALID_PHONE", "Нужен российский номер телефона.")
        digits = re.sub(r"[^0-9]", "", phone)
        if len(digits) == 11 and digits[0] in "78":
            digits = digits[1:]
        if len(digits) != 10:
            raise Failure("INVALID_PHONE", "Нужны 10 цифр либо номер с +7 или 8.")
        args["phone"] = digits
    if "code" in args and not re.fullmatch(r"[0-9]{1,16}", args["code"]):
        raise Failure("INVALID_ARGUMENT", "SMS-код должен быть строкой цифр.")
    if "product_id" in args and (type(args["product_id"]) is not int or args["product_id"] <= 0):
        raise Failure("INVALID_ARGUMENT", "ID товара должен быть положительным целым числом.")
    if "product_ids" in args:
        ids = args["product_ids"]
        if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(type(i) is not int or i <= 0 for i in ids):
            raise Failure("INVALID_ARGUMENT", "Нужны от 1 до 100 положительных ID товаров.")
        if len(set(ids)) != len(ids):
            raise Failure("UNSUPPORTED_QUANTITY", "Повторы ID не задают количество; используйте уникальные ID.")
        args["product_ids"] = sorted(ids)
    if method == "get_completed_orders":
        args.setdefault("limit", 20)
        if type(args["limit"]) is not int or not 1 <= args["limit"] <= 20:
            raise Failure("INVALID_ARGUMENT", "limit должен быть от 1 до 20.")
    if method == "add_to_cart":
        args.setdefault("substitution_policy", "none")
        if args["substitution_policy"] != "none":
            raise Failure("UNSUPPORTED_OPERATION", "Выберите согласованные аналоги агентом и добавьте их ID отдельно.")
    return args


class Service:
    def __init__(self, store, client, clock=None):
        self.store = store
        self.auth = Auth(store, client, **({"clock": clock} if clock else {}))
        self.shop = Shop(self.auth)

    def execute(self, method, arguments):
        request_id = None
        operation = None
        new_operation = False
        try:
            args = validate(method, arguments)
            self.auth.status()  # Expire abandoned phone challenges even on non-auth calls.
            self.store.expire_results(self.auth.clock())
            if method in JOURNALED:
                request_id = args["request_id"]
                fingerprint = self.store.fingerprint({"method": method, "args": args})
                operation = self.store.operation(request_id)
                if operation:
                    return self.replay(method, args, operation, fingerprint)
                if method in CART_METHODS:
                    self.auth.credentials()
                operation = {"method": method, "fingerprint": fingerprint,
                             "epoch": self.store.get("epoch"), "status": "running",
                             "created": self.auth.clock()}
                self.store.save_operation(request_id, operation)
                new_operation = True
            data = self.dispatch(method, args, operation)
            warnings = []
            if method == "add_to_cart":
                warnings.append("Добавлено по одному шагу API; шаг не гарантирует одну штуку или нужное число порций.")
            if method in CART_METHODS and data["outcome"] == "partial":
                warnings.append("Корзина изменена частично; проверьте skipped и фактическое количество.")
            result = success(data, warnings)
            if operation is not None:
                operation.update(status="done", result=result, epoch=self.store.get("epoch"))
                self.store.save_operation(request_id, operation)
            return result
        except Failure as failure:
            result = failure.result(request_id)
            if new_operation and operation["status"] == "running":
                uncertain = failure.code in ("OPERATION_OUTCOME_UNKNOWN", "UPSTREAM_PROTOCOL_ERROR")
                operation.update(status="unknown" if uncertain else "done", result=result)
                self.store.save_operation(request_id, operation)
            return result

    def replay(self, method, args, operation, fingerprint):
        if operation["fingerprint"] != fingerprint or operation["epoch"] != self.store.get("epoch"):
            raise Failure("REQUEST_ID_CONFLICT", "request_id относится к другой операции или сессии аккаунта.")
        if operation["status"] == "expired":
            raise Failure("REQUEST_ID_EXPIRED", "Срок хранения результата истёк; старая операция не повторена.")
        if operation["status"] in ("running", "unknown"):
            if method in CART_METHODS:
                self.auth.credentials()
                return self.shop.unknown(args["request_id"], operation)
            raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход предыдущего входа неизвестен; запрос не повторён.")
        result = operation["result"]
        if result["ok"] and method == "start_phone_auth":
            self.auth.pending(result["data"]["auth_session_id"])
        if result["ok"] and method == "submit_sms_code":
            return success(self.auth.status())
        return result

    def dispatch(self, method, args, operation):
        if method == "get_auth_status":
            return self.auth.status()
        if method == "start_phone_auth":
            return self.auth.start(args)
        if method == "submit_sms_code":
            return self.auth.submit(args)
        if method == "resend_sms_code":
            self.auth.pending(args["auth_session_id"])
            raise Failure("UNSUPPORTED_OPERATION", "Повторная SMS в той же гостевой сессии ещё не подтверждена API.")
        if method == "cancel_phone_auth":
            return self.auth.cancel(args)
        if method == "logout":
            return self.auth.logout()
        if method in CART_METHODS:
            return self.shop.mutate(method, args, operation)
        handlers = {"search_products": self.shop.search, "get_product_analogs": self.shop.analogs,
                    "get_completed_orders": self.shop.orders, "get_order_details": self.shop.details}
        self.auth.credentials()
        return self.shop.cart() if method == "get_cart" else handlers[method](args)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def main():
    try:
        if len(sys.argv) != 2:
            raise ValueError("method required")
        raw = sys.stdin.read(65537)
        if len(raw) > 65536:
            raise ValueError("input too large")
        arguments = json.loads(raw, object_pairs_hook=unique_object,
                               parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
        if not isinstance(arguments, dict):
            raise ValueError("object required")
    except (ValueError, UnicodeError):
        print(json.dumps(Failure("INVALID_ARGUMENT", "Нужны метод и один JSON-объект через stdin.").result(),
                         ensure_ascii=False))
        return 2
    try:
        with Store(state_directory()).locked() as store:
            result = Service(store, Client()).execute(sys.argv[1], arguments)
        exit_code = 0
    except Failure as failure:
        result, exit_code = failure.result(), 0
    except (OSError, sqlite3.Error):
        result, exit_code = Failure("STORAGE_UNAVAILABLE", "Не удалось сохранить или прочитать состояние.").result(), 1
    except Exception:
        # Exception strings and tracebacks may contain HTTP headers, codes, or bodies.
        result, exit_code = Failure("INTERNAL_ERROR", "Внутренняя ошибка скилла; повтор мутации не разрешён.").result(), 1
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
