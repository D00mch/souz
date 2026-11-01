"""Souz CLI: method in argv, one JSON object on stdin/stdout."""
import json
import re
import sqlite3
import sys

sys.dont_write_bytecode = True

from vit_auth import Auth
from vit_client import Client
from vit_common import Failure, success
from vit_menu import Menu, PICKUPS
from vit_orders import Orders, COLORS, identifier
from vit_storage import Store, state_directory


FIELDS = {
    "get_auth_status": (set(), set()), "logout": (set(), set()),
    "start_phone_auth": ({"phone", "request_id"}, set()),
    "submit_sms_code": ({"auth_session_id", "code", "request_id"}, set()),
    "cancel_phone_auth": ({"auth_session_id"}, set()),
    "search_restaurants": ({"query"}, {"city", "pickup", "limit"}),
    "get_restaurant": ({"restaurant_id"}, set()),
    "select_restaurant": ({"restaurant_id", "pickup", "request_id"}, set()),
    "search_products": ({"query"}, {"limit"}),
    "get_product": ({"product_id"}, set()),
    "get_cart": (set(), set()),
    "set_cart_items": ({"items", "request_id"}, set()),
    "clear_cart": ({"request_id"}, set()),
    "calculate_cart": (set(), set()),
    "prepare_checkout": ({"email"}, {"parking_zone", "vehicle_plate", "vehicle_color"}),
    "create_order": ({"checkout_id", "request_id"}, set()),
    "get_order_status": (set(), {"order_id"}),
}
AUTH_WRITES = {"start_phone_auth", "submit_sms_code"}
JOURNALED = AUTH_WRITES | {"select_restaurant", "set_cart_items", "clear_cart", "create_order"}


def validate(method, arguments):
    if method not in FIELDS:
        raise Failure("UNSUPPORTED_OPERATION", "Метод не входит в скилл.")
    required, optional = FIELDS[method]
    if not isinstance(arguments, dict) or not required <= arguments.keys() or arguments.keys() - required - optional:
        raise Failure("INVALID_ARGUMENT", "Проверьте обязательные и неизвестные поля.")
    args = dict(arguments)
    for key in ("phone", "code", "auth_session_id", "request_id", "query", "city", "pickup",
                "email", "checkout_id", "order_id", "vehicle_plate", "vehicle_color"):
        if key in args:
            value = args[key]
            if not isinstance(value, str) or not value.strip() or len(value) > 200:
                raise Failure("INVALID_ARGUMENT", "Нужна непустая строка допустимой длины: " + key)
            args[key] = value.strip()
    if "email" in args and (len(args["email"]) > 200 or
                            not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", args["email"])):
        raise Failure("INVALID_ARGUMENT", "Нужен email для чека.")
    for key in ("checkout_id", "order_id"):
        if key in args and not identifier(args[key]):
            raise Failure("INVALID_ARGUMENT", "Недопустимый ID: " + key)
    if "parking_zone" in args and (type(args["parking_zone"]) is not int or args["parking_zone"] < 0):
        raise Failure("INVALID_ARGUMENT", "Нужен целый номер зоны парковки.")
    if "vehicle_color" in args and args["vehicle_color"] not in COLORS:
        raise Failure("INVALID_ARGUMENT", "Неизвестный цвет машины.")
    if "vehicle_plate" in args:
        args["vehicle_plate"] = args["vehicle_plate"].upper()
        if not re.fullmatch(r"[АВЕКМНОРСТУХABEKMHOPCTYX][0-9]{3}[АВЕКМНОРСТУХABEKMHOPCTYX]{2}[0-9]{2,3}", args["vehicle_plate"]):
            raise Failure("INVALID_ARGUMENT", "Нужен госномер машины без пробелов, например А123ВС77.")
    if "phone" in args:
        if not re.fullmatch(r"\+?[0-9 ()-]+", args["phone"]):
            raise Failure("INVALID_PHONE", "Нужен российский номер телефона.")
        digits = re.sub(r"\D", "", args["phone"])
        if len(digits) == 11 and digits[0] in "78":
            digits = digits[1:]
        if len(digits) != 10:
            raise Failure("INVALID_PHONE", "Нужны 10 цифр либо номер с +7 или 8.")
        args["phone"] = "+7" + digits
    if "code" in args and not re.fullmatch(r"[0-9]{1,16}", args["code"]):
        raise Failure("INVALID_ARGUMENT", "Код должен быть строкой цифр с сохранением ведущих нулей.")
    if "pickup" in args and args["pickup"] not in PICKUPS:
        raise Failure("INVALID_ARGUMENT", "Неизвестный способ получения.")
    for key in ("restaurant_id", "product_id"):
        if key in args and (type(args[key]) is not int or args[key] <= 0):
            raise Failure("INVALID_ARGUMENT", "Нужен положительный целочисленный ID: " + key)
    if method in ("search_restaurants", "search_products"):
        args.setdefault("limit", 10)
        if type(args["limit"]) is not int or not 1 <= args["limit"] <= 30:
            raise Failure("INVALID_ARGUMENT", "limit должен быть от 1 до 30.")
        if not re.search(r"\w", args["query"]):
            raise Failure("INVALID_ARGUMENT", "Запрос должен содержать буквы или цифры.")
    if "items" in args:
        items = args["items"]
        if not isinstance(items, list) or not 1 <= len(items) <= 50:
            raise Failure("INVALID_ARGUMENT", "Нужны от 1 до 50 позиций.")
        seen = set()
        for item in items:
            if not isinstance(item, dict) or set(item) != {"product_id", "quantity"}:
                raise Failure("INVALID_ARGUMENT", "Позиция содержит product_id и quantity.")
            pid, qty = item["product_id"], item["quantity"]
            if type(pid) is not int or pid <= 0 or type(qty) is not int or not 0 <= qty <= 20 or pid in seen:
                raise Failure("INVALID_ARGUMENT", "Нужны уникальные ID и целое количество от 0 до 20.")
            seen.add(pid)
        args["items"] = sorted(items, key=lambda item: item["product_id"])
    return args


class Service:
    def __init__(self, store, client, clock=None):
        self.store = store
        self.auth = Auth(store, client, **({"clock": clock} if clock else {}))
        self.menu = Menu(store, self.auth)
        self.orders = Orders(store, self.auth, self.menu)

    def execute(self, method, arguments):
        request_id, operation, new_operation = None, None, False
        try:
            args = validate(method, arguments)
            self.auth.status()
            self.store.expire_results(self.auth.clock())
            if method in JOURNALED:
                request_id = args["request_id"]
                fingerprint = self.store.fingerprint({"method": method, "args": args})
                operation = self.store.operation(request_id)
                if operation:
                    if operation["fingerprint"] != fingerprint or operation["epoch"] != self.store.get("epoch"):
                        raise Failure("REQUEST_ID_CONFLICT", "ID относится к другим параметрам или сессии.")
                    if operation["status"] == "expired":
                        raise Failure("REQUEST_ID_EXPIRED", "Результат устарел; операция не повторена.")
                    if operation["status"] != "done":
                        raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход предыдущего вызова неизвестен.")
                    if operation["result"]["ok"] and method == "start_phone_auth":
                        self.auth.pending(operation["result"]["data"]["auth_session_id"])
                    return success(self.auth.status()) if method == "submit_sms_code" and operation["result"]["ok"] else operation["result"]
                operation = {"fingerprint": fingerprint, "epoch": self.store.get("epoch"),
                             "status": "running", "created": self.auth.clock()}
                self.store.save_operation(request_id, operation)
                new_operation = True
            data = self.dispatch(method, args)
            warnings = []
            if method in ("get_cart", "select_restaurant", "set_cart_items", "calculate_cart", "clear_cart"):
                warnings.append("Это черновик Союза; корзина приложения не изменена.")
            if method == "calculate_cart" and not data["quote"]["confirmed"]:
                warnings.append("Сервер не подтвердил все позиции; проверьте ответ расчёта.")
            if method in ("calculate_cart", "prepare_checkout"):
                optimization = (data.get("quote") or data).get("optimization", {})
                if optimization.get("status") == "partial":
                    warnings.append("Выгодные варианты проверены не полностью; использован подтверждённый расчёт.")
            if method == "create_order":
                if data["amount_changed"]:
                    warnings.append("Сумма заказа изменилась при оформлении; согласуйте новую сумму до оплаты.")
                if not data["payment_link_available"] and data["payment_state"] == "pending":
                    warnings.append("Заказ создан, но подходящая HTTPS-ссылка не получена; проверьте статус, не создавайте новый заказ.")
            result = success(data, warnings)
        except Failure as failure:
            result = failure.result(request_id)
            # Never alter a pre-existing journal entry on conflict or replay.
            if new_operation and operation and operation.get("status") == "running":
                stored = self.store.operation(request_id)
                if stored and stored["status"] == "running" and stored["fingerprint"] == operation["fingerprint"]:
                    uncertain = method in (AUTH_WRITES | {"create_order"}) and failure.code in ("OPERATION_OUTCOME_UNKNOWN", "UPSTREAM_PROTOCOL_ERROR")
                    stored.update(status="unknown" if uncertain else "done", result=result)
                    self.store.save_operation(request_id, stored)
            return result
        if operation:
            operation.update(status="done", result=result, epoch=self.store.get("epoch"))
            self.store.save_operation(request_id, operation)
        return result

    def dispatch(self, method, args):
        handlers = {"get_auth_status": lambda: self.auth.status(), "logout": lambda: self.auth.logout(),
                    "start_phone_auth": lambda: self.auth.start(args),
                    "submit_sms_code": lambda: self.auth.submit(args),
                    "cancel_phone_auth": lambda: self.auth.cancel(args),
                    "search_restaurants": lambda: self.menu.search_restaurants(args),
                    "get_restaurant": lambda: self.menu.restaurant(args["restaurant_id"]),
                    "select_restaurant": lambda: self.menu.select(args),
                    "search_products": lambda: self.menu.search_products(args),
                    "get_product": lambda: self.menu.get_product(args),
                    "get_cart": lambda: self.menu.cart(), "set_cart_items": lambda: self.menu.set_items(args),
                    "clear_cart": lambda: self.menu.clear(), "calculate_cart": lambda: self.menu.calculate(),
                    "prepare_checkout": lambda: self.orders.prepare(args),
                    "create_order": lambda: self.orders.create(args),
                    "get_order_status": lambda: self.orders.status(args)}
        return handlers[method]()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def main():
    try:
        if len(sys.argv) != 2:
            raise ValueError("method required")
        raw = sys.stdin.read(65537)
        if len(raw) > 65536:
            raise ValueError("input too large")
        args = json.loads(raw, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("constant")))
        if not isinstance(args, dict):
            raise ValueError("JSON object required")
    except (ValueError, UnicodeError):
        print(json.dumps(Failure("INVALID_ARGUMENT", "Нужны метод и JSON-объект через stdin.").result(), ensure_ascii=False))
        return 2
    try:
        with Store(state_directory()).locked() as store:
            result = Service(store, Client(proxy_url=store.get("https_proxy"))).execute(sys.argv[1], args)
        exit_code = 0
    except Failure as failure:
        result, exit_code = failure.result(), 0
    except (OSError, sqlite3.Error):
        result, exit_code = Failure("STORAGE_UNAVAILABLE", "Хранилище недоступно.").result(), 1
    except Exception:
        result, exit_code = Failure("INTERNAL_ERROR", "Внутренняя ошибка скилла.").result(), 1
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
