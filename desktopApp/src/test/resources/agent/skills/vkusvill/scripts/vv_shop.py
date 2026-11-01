"""Catalog, recent purchase history, and verified cart mutations."""
from vv_common import Failure, array, external_id, iso, obj, product, protocol, quantity


class Shop:
    def __init__(self, auth):
        self.auth = auth
        self.store = auth.store

    def remember(self, products):
        account = self.auth.credentials()["number"]
        known = self.store.get("products:" + account, {})
        known.update({str(item["id"]): {"id": item["id"], "name": item["name"]} for item in products})
        self.store.put("products:" + account, known)

    def require_known(self, ids):
        known = self.store.get("products:" + self.auth.credentials()["number"], {})
        if any(str(item) not in known for item in ids):
            raise Failure("PRODUCT_NOT_FOUND", "Сначала получите товар из поиска, истории, корзины или аналогов.")

    def cart(self):
        answer = self.auth.request("GET", "/api/v1/cart/lite")
        data = obj(answer.get("data"))
        raw = data.get("cart")
        if raw is None:
            raise Failure("CART_CONTEXT_REQUIRED", "Настройте корзину и доставку в приложении ВкусВилла.")
        raw = obj(raw)
        cart_id = raw.get("id")
        products = []
        for item in array(raw.get("products")):
            item = obj(item)
            parsed = product(item.get("product_id"), obj(item.get("properties")).get("name"))
            remains = item.get("remains")
            stock = None if remains is None else obj(remains).get("quantity")
            parsed.update(quantity=quantity(item.get("quantity")),
                          in_stock=None if stock is None else quantity(stock) > 0)
            products.append(parsed)
        if len({item["id"] for item in products}) != len(products):
            raise protocol()
        self.remember(products)
        return {"cart_id": external_id(cart_id) if cart_id is not None else None,
                "products": products, "fetched_at": iso(self.auth.clock())}

    def search(self, args):
        answer = self.auth.request("GET", "/api/bff/get_screen_widgets",
                                   params={"screen": "CatalogSearch", "query": args["query"]})
        products = {}
        for widget in array(answer.get("widgets")):
            widget = obj(widget)
            if widget.get("code") != "search/primary":
                continue
            for item in array(widget.get("content")):
                item = obj(item)
                if item.get("type") != "product":
                    continue
                if type(item.get("in_stock")) is not bool:
                    raise protocol()
                button = obj(item.get("button") or {})
                meta = obj(button.get("meta") or {})
                if "is_today" in meta and type(meta["is_today"]) is not bool:
                    raise protocol()
                if item["in_stock"] and meta.get("is_today", True):
                    parsed = product(item.get("id"), item.get("title"))
                    products[parsed["id"]] = parsed
        result = list(products.values())
        self.remember(result)
        return {"products": result}

    def analogs(self, args):
        self.require_known([args["product_id"]])
        answer = self.auth.request("GET", "/api/v1/cart/product-analogs",
                                   params={"product_id": args["product_id"], "quantity": "1.0"})
        if answer.get("status") != "success":
            raise protocol()
        products = [product(obj(item).get("product_analog_id"), obj(item.get("properties")).get("name"))
                    for item in array(answer.get("data"))]
        self.remember(products)
        return {"products": products}

    def orders(self, args):
        answer = self.auth.request("GET", "/api/takeaway/getOrdersListNewCart/", params={"cnt": 20})
        orders = {}
        for group in array(answer.get("orders")):
            for item in array(obj(group).get("orders")):
                item = obj(item)
                if item.get("status_name") != "Успешно завершен":
                    continue
                order_id = external_id(item.get("id_order"))
                stamp = item.get("date_supply")
                supplied = None
                if type(stamp) in (int, float):
                    try:
                        supplied = iso(stamp)
                    except (ValueError, OverflowError, OSError):
                        raise protocol()
                orders[order_id] = {"id_order": order_id, "supplied_at": supplied}
        result = sorted(orders.values(), key=lambda row: row["supplied_at"] or "", reverse=True)
        self.store.put("orders:" + self.auth.credentials()["number"], orders)
        return {"orders": result[:args["limit"]], "requested_limit": args["limit"],
                "pagination_supported": False}

    def details(self, args):
        order_id = args["id_order"]
        key = "orders:" + self.auth.credentials()["number"]
        if order_id not in self.store.get(key, {}):
            self.orders({"limit": 20})
        summary = self.store.get(key, {}).get(order_id)
        if summary is None:
            raise Failure("ORDER_NOT_FOUND", "Заказ отсутствует в доступной истории текущего аккаунта.")
        answer = self.auth.request("GET", "/api/v2/takeaway/getOrder/", params={"id_order": order_id})
        purchases = []
        for item in array(answer.get("order_items")):
            item = obj(item)
            parsed = product(item.get("id_tov"), item.get("name"))
            measure = item.get("ed_izm")
            if measure is not None and not isinstance(measure, str):
                raise protocol()
            parsed.update(quantity=quantity(item.get("quantity")), measure=measure, category=None)
            purchases.append(parsed)
        self.remember(purchases)
        return {**summary, "purchases": purchases}

    def mutate(self, method, args, operation):
        before = self.cart()
        if not before["cart_id"]:
            raise Failure("CART_CONTEXT_REQUIRED", "Настройте корзину в приложении ВкусВилла.")
        ids = args.get("product_ids", [])
        if method == "add_to_cart":
            self.require_known(ids)
        before_ids = {item["id"] for item in before["products"]}
        sent_ids = [item for item in ids if method == "add_to_cart" or item in before_ids]
        operation.update(before=before, product_ids=ids, phase="planned")
        self.store.save_operation(args["request_id"], operation)
        if sent_ids or (method == "clear_cart" and before_ids):
            operation["phase"] = "sent"
            self.store.save_operation(args["request_id"], operation)
            try:
                self.auth.request("POST", "/api/v1/cart/products", json={
                    "cart_id": before["cart_id"], "delete_all": method == "clear_cart",
                    "products": [{"is_green_price": False, "product_id": item,
                                  "step": 1 if method == "add_to_cart" else -100}
                                 for item in sent_ids],
                })
            except Failure as failure:
                if failure.code in ("AUTH_EXPIRED", "AUTH_REQUIRED", "ACCESS_DENIED", "RATE_LIMITED"):
                    operation["phase"] = "rejected"
                    raise
                return self.unknown(args["request_id"], operation)
        try:
            after = self.cart()
        except Failure:
            return self.unknown(args["request_id"], operation, read_cart=False)
        # A changed cart context or unrelated change makes attribution unsafe.
        old = {item["id"]: item["quantity"] for item in before["products"]}
        new = {item["id"]: item["quantity"] for item in after["products"]}
        affected = set(ids) if method != "clear_cart" else set(old)
        unrelated = any(old.get(item) != new.get(item) for item in (old.keys() | new.keys()) - affected)
        if after["cart_id"] != before["cart_id"] or unrelated:
            return self.unknown(args["request_id"], operation, cart=after, read_cart=False)
        if method == "clear_cart":
            applied = sorted(old.keys() - new.keys())
            skipped = [{"product_id": item, "reason": "Позиция осталась после очистки."} for item in new]
        else:
            applied = [item for item in ids if (
                new.get(item, 0) > old.get(item, 0) if method == "add_to_cart" else item not in new)]
            skipped = [{"product_id": item, "reason": "Количество не увеличилось." if method == "add_to_cart"
                        else "Позиция осталась; полное удаление этим API не подтверждено."}
                       for item in ids if item not in applied]
        operation["phase"] = "verified"
        return {"operation_id": args["request_id"], "outcome": "partial" if skipped else "applied",
                "cart": after, "applied_product_ids": applied, "skipped": skipped, "substitutions": []}

    def unknown(self, request_id, operation, cart=None, read_cart=True):
        if read_cart:
            try:
                cart = self.cart()
            except Failure:
                pass
        operation["status"] = "unknown"
        self.store.save_operation(request_id, operation)
        mutation = {"operation_id": request_id, "outcome": "unknown", "cart": cart,
                    "applied_product_ids": [], "skipped": [], "substitutions": []}
        raise Failure("OPERATION_OUTCOME_UNKNOWN",
                      "Изменение могло выполниться. Корзина перечитана по возможности; повтор не отправлен.",
                      details={"mutation": mutation, "cart_before": operation.get("before")})
