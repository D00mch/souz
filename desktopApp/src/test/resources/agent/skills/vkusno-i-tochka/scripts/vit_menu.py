"""Restaurant discovery, local draft cart and provider-confirmed quotes."""
import datetime
import re

from vit_client import TOTAL
from vit_common import Failure, array, iso, number, obj, protocol, text
from vit_deals import Deals, checked_quote


PICKUPS = {"auto": ("mcauto", 3), "takeaway": ("inStoreTakeAway", 4),
           "eat_in": ("inStoreEatIn", 5), "parking": ("curbside", 2),
           "table": ("tableService", 1), "night_window": ("nightWindow", 7),
           "locker": ("locker", 9)}


def normalized(value):
    return str(value or "").casefold().replace("ё", "е")


def matches(query, value):
    words = re.findall(r"\w+", normalized(value))
    return all(any(word.startswith(needle[:5] if len(needle) > 5 else needle)
                   for word in words) for needle in re.findall(r"\w+", normalized(query)))


class Menu:
    def __init__(self, store, auth):
        self.store, self.auth, self.clock = store, auth, auth.clock

    def cached(self, key, path, ttl=300):
        cached = self.store.get(key)
        if cached and 0 <= self.clock() - cached["at"] < ttl:
            return cached["items"]
        answer = self.auth.client.request("GET", path, headers=self.auth.headers(),
                                          params={"modified": 0})
        items = [obj(item) for item in array(answer.get("items")) if not obj(item).get("deleted")]
        self.store.put(key, {"at": self.clock(), "items": items})
        return items

    def restaurants(self):
        cities = {city["id"]: city for city in
                  self.cached("cities", "/api/v1/catalog/cities", 3600) if "id" in city}
        result = []
        for source in self.cached("restaurants", "/api/v1/catalog/restaurants"):
            store_id = source.get("store_id")
            if (type(store_id) is not int or store_id <= 0 or source.get("openingSoon") or
                    source.get("temporarilyClosed")):
                continue
            city = cities.get(source.get("city"), {})
            way = source.get("wayOfGet") or {}
            if not isinstance(way, dict):
                raise protocol()
            result.append({"restaurant_id": store_id, "name": text(source.get("name")),
                           "address": text(source.get("address")), "city": city.get("name"),
                           "region": city.get("region"), "city_id": source.get("city"), "catalog_id": source.get("id"),
                           "timezone": city.get("timezone"), "location": source.get("location"),
                           "physical_auto": source.get("mcauto") is True,
                           "parking_zone_range": self.parking_zones(source),
                           "mobile_pickups": [name for name, (key, _) in PICKUPS.items()
                                              if way.get(key) is True],
                           "hours": self.today_hours(source, city.get("timezone"))})
        return result

    def parking_zones(self, restaurant):
        bounds = restaurant.get("curbsideNums")
        if bounds is None:
            return None
        if (not isinstance(bounds, list) or len(bounds) != 2 or
                any(type(value) is not int for value in bounds) or not 0 <= bounds[0] <= bounds[1]):
            raise protocol()
        return {"min": bounds[0], "max": bounds[1]}

    def today_hours(self, restaurant, timezone):
        match = re.fullmatch(r"UTC([+-])(\d{1,2}):(\d{2})", str(timezone))
        if not match:
            return {"timezone": timezone, "today": None, "halls": []}
        offset = (int(match[2]) * 60 + int(match[3])) * (1 if match[1] == "+" else -1)
        if abs(offset) >= 1440:
            raise protocol()
        zone = datetime.timezone(datetime.timedelta(minutes=offset))
        today = datetime.datetime.fromtimestamp(self.clock(), zone)
        weekday = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")[today.weekday()]
        halls = []
        for hall in restaurant.get("halls", []):
            periods = [item for item in hall.get("worktime", []) if item.get("name") == weekday]
            halls.append({"name": hall.get("name"), "type": hall.get("type"), "periods": periods})
        return {"timezone": timezone, "today": today.date().isoformat(), "halls": halls}

    def search_restaurants(self, args):
        result = []
        for restaurant in self.restaurants():
            if args.get("city") and normalized(args["city"]) != normalized(restaurant["city"]):
                continue
            if args.get("pickup") and args["pickup"] not in restaurant["mobile_pickups"]:
                continue
            searchable = " ".join(str(restaurant.get(key) or "") for key in
                                  ("name", "address", "city", "region"))
            if matches(args["query"], searchable):
                result.append(restaurant)
        result.sort(key=lambda item: (normalized(item["city"]) != normalized(args["query"]),
                                      normalized(item["city"]), normalized(item["address"])))
        return {"restaurants": result[:args["limit"]], "total_matches": len(result),
                "fetched_at": iso(self.clock()), "availability": "catalog; inspect hours"}

    def restaurant(self, restaurant_id):
        result = next((item for item in self.restaurants() if item["restaurant_id"] == restaurant_id), None)
        if result is None:
            raise Failure("RESTAURANT_NOT_FOUND", "Ресторан не найден среди доступных предприятий.")
        return result

    def selected(self, fresh=False):
        selected = self.store.get("selected")
        if not selected:
            raise Failure("RESTAURANT_REQUIRED", "Сначала выберите ресторан и способ получения.")
        if fresh:
            self.store.put("restaurants", None)
        restaurant = self.restaurant(selected["restaurant_id"])
        if selected["pickup"] not in restaurant["mobile_pickups"]:
            raise Failure("PICKUP_UNAVAILABLE", "Мобильное получение этим способом недоступно.")
        return {**restaurant, "pickup": selected["pickup"]}

    def select(self, args):
        restaurant = self.restaurant(args["restaurant_id"])
        if args["pickup"] not in restaurant["mobile_pickups"]:
            raise Failure("PICKUP_UNAVAILABLE", "Мобильное получение этим способом недоступно.")
        self.store.update({"selected": {"restaurant_id": args["restaurant_id"], "pickup": args["pickup"]},
                           "quote": None})
        return self.cart()

    def menu(self):
        restaurant = self.selected()
        # Compact catalog cache excludes images and legacy/deleted items.
        cached = self.store.get("products")
        if not cached or cached.get("schema") != 2 or not 0 <= self.clock() - cached["at"] < 900:
            source = self.auth.client.request("GET", "/api/v1/catalog/products",
                                             headers=self.auth.headers(city_id=restaurant["city_id"]),
                                             params={"modified": 0})
            fields = ("productId", "name", "size", "weight", "energyKcal", "nutritious", "ingredients",
                      "allergens", "description", "unavailableMcAuto", "dayParts", "sorting")
            products = [{key: item.get(key) for key in fields}
                        for item in array(source.get("items")) if not obj(item).get("deleted") and
                        type(item.get("productId")) is int and item["productId"] > 0]
            cached = {"at": self.clock(), "items": products, "city_id": restaurant["city_id"], "schema": 2}
            self.store.put("products", cached)
        if cached.get("city_id") != restaurant["city_id"]:
            self.store.put("products", None)
            return self.menu()
        prices = self.auth.client.request("GET", "/api/v2/order/prices",
                                         headers=self.auth.headers(city_id=restaurant["city_id"]),
                                         params={"store_id": restaurant["restaurant_id"]})
        prices = obj(prices.get("items"))
        self.price_records = prices
        self.catalog_records = {item["productId"]: item for item in cached["items"]}
        result = {}
        for product in cached["items"]:
            price = prices.get(str(product["productId"]))
            if not isinstance(price, dict) or price.get("product_class") not in ("PRODUCT", "VALUE_MEAL"):
                continue
            if price.get("outage") is not False or "PICKUP" not in price.get("dist_points", []):
                continue
            if restaurant["pickup"] == "auto" and product.get("unavailableMcAuto") is True:
                continue
            item = {"product_id": product["productId"], "name": text(product["name"]),
                    "size": product.get("size"), "weight_g": product.get("weight"),
                    "energy_kcal": product.get("energyKcal"), "nutrition": product.get("nutritious"),
                    "ingredients": product.get("ingredients"), "allergens": product.get("allergens"),
                    "description": product.get("description"), "day_parts": price.get("day_parts"),
                    "product_class": price["product_class"],
                    "preliminary_price": number(price.get("price")), "price_confirmed": False,
                    "has_choices": bool(price.get("choices")), "availability": "preliminary"}
            result[item["product_id"]] = item
        return result

    def standard_products(self, cart, menu, pickup, *, as_composition=False):
        """Serialize unmodified products like Android, including bottom-level choices.

        Nested choice groups and meals need explicit selection support. Refuse them
        instead of sending a flat product that the price service may still accept.
        """
        count = 0

        def unsupported(product_id):
            raise Failure("PRODUCT_CONFIGURATION_UNSUPPORTED",
                          "Стандартный состав нельзя надёжно собрать; выберите другой отдельный продукт.",
                          details={"product_id": product_id})

        def record(product_id):
            source = self.price_records.get(str(product_id))
            if not isinstance(source, dict):
                unsupported(product_id)
            return source

        def available(product_id):
            source = record(product_id)
            return (source.get("outage") is False and "PICKUP" in source.get("dist_points", []) and
                    not (pickup == "auto" and
                         self.catalog_records.get(product_id, {}).get("unavailableMcAuto") is True))

        def quantity(edge):
            value = edge.get("default_qty")
            minimum, maximum = edge.get("min_qty", 0), edge.get("max_qty")
            if (any(type(n) is not int for n in (value, minimum, maximum)) or
                    not 0 <= minimum <= value <= maximum <= 100 or
                    type(edge.get("product_id")) is not int or
                    not isinstance(edge.get("group_id"), str) or not edge["group_id"]):
                unsupported(edge.get("product_id"))
            return value

        def node(product_id, qty, mode="root", group=None, path=()):
            nonlocal count
            count += 1
            if count > 500 or len(path) >= 8 or product_id in path:
                unsupported(product_id)
            source = record(product_id)
            if source.get("product_class") not in ("PRODUCT", "RAW_ITEM", "COMMENT", "NON_FOOD_PRODUCT"):
                unsupported(product_id)
            if qty > 0 and not available(product_id):
                unsupported(product_id)
            result = {"productId": product_id, "qty": qty, "type": "product",
                      "productClass": source["product_class"]}
            if group is not None:
                result["groupId"] = group
            path = (*path, product_id)
            choices = []
            for edge in array(source.get("choices", [])):
                edge = obj(edge)
                choice_qty = quantity(edge)
                if not choice_qty:
                    continue
                choice_id = edge["product_id"]
                choice = record(choice_id)
                if (choice_id in path or choice.get("product_class") != "CHOICE" or
                        choice.get("choices") or not available(choice_id)):
                    unsupported(choice_id)
                candidates = []
                parent_parts = source.get("day_parts")
                for option in array(choice.get("composition", [])):
                    option = obj(option)
                    child = option.get("product_id")
                    if type(child) is not int:
                        unsupported(choice_id)
                    if not available(child):
                        continue
                    catalog = self.catalog_records.get(child, {})
                    sorting = catalog.get("sorting")
                    child_parts = record(child).get("day_parts")
                    # Avoid guessing a time/menu-dependent default without its context.
                    if (type(sorting) is not int or not isinstance(parent_parts, list) or
                            not isinstance(child_parts, list) or len(parent_parts) != 3 or
                            len(child_parts) != 3 or
                            any(a and not b for a, b in zip(parent_parts, child_parts))):
                        unsupported(choice_id)
                    candidates.append((sorting, option))
                candidates.sort(key=lambda item: item[0])
                if (not candidates or (len(candidates) > 1 and candidates[0][0] == candidates[1][0]) or
                        candidates[0][1].get("cost_inclusive") is not True):
                    unsupported(choice_id)
                option = candidates[0][1]
                # Android chooses the first available catalog-sorted option, even
                # when the option's own default_qty is zero. The group sets its qty.
                child_id = option["product_id"]
                if (record(child_id).get("product_class") != "PRODUCT" or
                        option.get("group_id") != edge["group_id"] or
                        type(option.get("max_qty")) is not int or option["max_qty"] < 1):
                    unsupported(choice_id)
                child = node(child_id, choice_qty, "composition", option["group_id"], (*path, choice_id))
                choices.append({"productId": choice_id, "qty": choice_qty, "type": "product",
                                "productClass": "CHOICE", "groupId": edge["group_id"],
                                "composition": [child]})
            if choices:
                result["choices"] = choices
            composition = []
            for edge in array(source.get("composition", [])):
                edge = obj(edge)
                component_qty = quantity(edge)
                component_id = edge["product_id"]
                component_class = record(component_id).get("product_class")
                # Standard RAW_ITEM ingredients are implicit. Inside a composition
                # item Android only sends exclusions (qty zero), not defaults.
                if component_qty == 0 or (mode == "root" and component_class == "PRODUCT"):
                    composition.append(node(component_id, component_qty, "composition", edge["group_id"], path))
            if composition:
                result["composition"] = composition
            return result

        return [node(item["product_id"], item["quantity"], "composition" if as_composition else "root") for item in cart
                if item["product_id"] in menu]

    def search_products(self, args):
        products = [item for item in self.menu().values()
                    if matches(args["query"], item["name"] + " " + str(item["size"] or ""))]
        products.sort(key=lambda item: (normalized(item["name"]) != normalized(args["query"]),
                                       normalized(item["name"]), item["product_id"]))
        return {"products": products[:args["limit"]], "total_matches": len(products),
                "restaurant_id": self.store.get("selected")["restaurant_id"]}

    def get_product(self, args):
        product = self.menu().get(args["product_id"])
        if not product:
            raise Failure("PRODUCT_UNAVAILABLE", "Товар не найден в текущем меню выбранного ресторана.")
        return product

    def cart(self):
        return {"kind": "souz_draft", "visible_in_provider_app": False,
                "restaurant": self.store.get("selected"), "items": self.store.get("cart", []),
                "quote": self.store.get("quote"), "revision": self.store.get("revision", 0)}

    def set_items(self, args):
        menu = self.menu()
        cart = {item["product_id"]: item for item in self.store.get("cart", [])}
        for item in args["items"]:
            product_id, quantity = item["product_id"], item["quantity"]
            if quantity == 0:
                cart.pop(product_id, None)
                continue
            product = menu.get(product_id)
            if product is None:
                raise Failure("PRODUCT_UNAVAILABLE", "Товар недоступен; черновик не изменён.",
                              details={"product_id": product_id})
            cart[product_id] = {"product_id": product_id, "name": product["name"],
                                "quantity": quantity, "product_class": product["product_class"]}
        if len(cart) > 50 or sum(item["quantity"] for item in cart.values()) > 100:
            raise Failure("INVALID_ARGUMENT", "Допустимо до 50 позиций и 100 единиц в черновике.")
        self.store.update({"cart": list(cart.values()), "quote": None,
                           "revision": self.store.get("revision", 0) + 1})
        return self.cart()

    def clear(self):
        self.store.update({"cart": [], "quote": None, "revision": self.store.get("revision", 0) + 1})
        return self.cart()

    @staticmethod
    def pickup_code(restaurant):
        return PICKUPS[restaurant["pickup"]][1]

    def calculate(self):
        # Invalidate before auth, restaurant refresh or any other failure can occur.
        self.store.update({"quote": None, "quoted_products": None, "quoted_sp_products": None})
        self.auth.credentials()
        restaurant = self.selected(fresh=True)
        cart = self.store.get("cart", [])
        if not cart:
            raise Failure("CART_EMPTY", "Черновик корзины пуст.")
        menu = self.menu()
        unavailable = [item["product_id"] for item in cart if item["product_id"] not in menu]
        if unavailable:
            raise Failure("PRODUCT_UNAVAILABLE", "Состав изменился; проверьте недоступные позиции.",
                          details={"product_ids": unavailable})
        body = {"storeId": str(restaurant["restaurant_id"]),
                "wayOfGet": PICKUPS[restaurant["pickup"]][1],
                "products": self.standard_products(cart, menu, restaurant["pickup"]),
                "spProducts": [], "calculateLoyalty": False}
        answer = self.auth.request("POST", TOTAL, city_id=restaurant["city_id"], json=body)
        baseline = checked_quote(answer, body["products"], [])
        quoted = [{**item, "name": next(x["name"] for x in cart if x["product_id"] == item["product_id"])}
                  for item in baseline["items"]]
        best, products, specials, applied = baseline, body["products"], [], []
        optimization = {"status": "not_checked", "additional_quotes": 0,
                        "reasons": [], "globally_optimal": False}
        if baseline["confirmed"]:
            best, products, specials, applied, optimization = Deals(
                self, restaurant, menu, cart, baseline, body["products"]).run()
        quote = {"confirmed": best["confirmed"], "total": best["total"], "currency": "RUB", "items": quoted,
                 "original_total": baseline["total"], "savings": round(baseline["total"] - best["total"], 2),
                 "items_price_basis": "before_optimization", "applied_deals": applied,
                 "optimization": optimization,
                 "unavailable_product_ids": best["unavailable_product_ids"], "quoted_at": iso(self.clock()),
                 "cart_revision": self.store.get("revision", 0)}
        self.store.update({"quote": quote, "quoted_products": products if best["confirmed"] else None,
                           "quoted_sp_products": specials if best["confirmed"] else None})
        return {**self.cart(), "restaurant_details": restaurant}
