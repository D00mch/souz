"""Bounded, exact-content packing; only server-confirmed savings can replace a quote."""
from collections import Counter
from copy import deepcopy
import re
import time

from vit_client import TOTAL, OFFERS, ORDER_CONFIG, actual_offer_path
from vit_common import Failure, array, number, obj, protocol

# Explicit menu SKU pairs, guarded by names and size/volume. No fuzzy food matching.
ALIASES = {
    8883: (3030, 'Картофель Фри Большой', 'Картофель Фри большой', None),
    8882: (3010, 'Картофель Фри Средний', 'Картофель Фри средний', None),
    7557: (7232, 'Добрый Кола 0,4 л', 'Добрый Кола (сред.)', 400),
    7558: (7233, 'Добрый Кола 0,5 л', 'Добрый Кола (бол.)', 500),
    8916: (7148, 'Чай черный Эрл Грей (мал.)', 'Чай черный Эрл Грей (мал.)', 200),
    8919: (7149, 'Чай черный Эрл Грей (сред.)', 'Чай черный Эрл Грей (сред.)', 300),
}
PLACEHOLDERS = {40000100: 'Выбери соус', 40000105: 'Добавь продукт на выбор'}
MAX_CANDIDATES, MAX_PLANS, MAX_QUOTES = 64, 24, 5
OPTIONAL_FAILURES = {'REQUEST_REJECTED', 'ACCESS_DENIED', 'RATE_LIMITED',
                     'UPSTREAM_UNAVAILABLE', 'UPSTREAM_PROTOCOL_ERROR'}


def checked_lines(rows, products):
    expected = Counter()
    for item in products:
        expected[item['productId']] += item['qty']
    seen, parsed = set(), []
    for source in array(rows):
        source = obj(source)
        pid, qty, price = source.get('pId'), source.get('qty'), source.get('uPrice')
        if type(pid) is not int or pid not in expected or pid in seen or type(qty) is not int or qty < 0:
            raise protocol()
        seen.add(pid)
        price = number(price) if price is not None else None
        parsed.append({'product_id': pid, 'quantity': qty, 'unit_price': price,
                       'accepted': not source.get('error') and qty == expected[pid] and price is not None})
    return parsed, seen == set(expected) and all(row['accepted'] for row in parsed)


def checked_quote(answer, products, specials):
    total = number(answer.get('total'))
    outages = array(answer.get('outage', []))
    if any(type(value) is not int for value in outages):
        raise protocol()
    lines, valid = checked_lines(answer.get('prices') or [], products)
    expected = {item['offerId']: item for item in specials}
    if len(expected) != len(specials):
        raise protocol()
    seen, special_lines = set(), []
    for row in array(answer.get('spPrices') or []):
        row = obj(row)
        offer_id = row.get('offerId')
        if not isinstance(offer_id, str) or offer_id not in expected or offer_id in seen:
            raise protocol()
        seen.add(offer_id)
        parsed, accepted = checked_lines(row.get('products') or [], expected[offer_id]['products'])
        valid = valid and accepted and not row.get('error')
        special_lines.extend(parsed)
    valid = bool(valid and seen == set(expected) and not outages)
    line_total = sum(row['quantity'] * row['unit_price'] for row in lines + special_lines
                     if row['unit_price'] is not None)
    valid = valid and abs(line_total - total) <= 0.01
    return {'confirmed': valid, 'total': total, 'items': lines, 'unavailable_product_ids': outages}


class Deals:
    def __init__(self, menu, restaurant, catalog, cart, baseline, products):
        self.menu, self.auth, self.restaurant, self.catalog = menu, menu.auth, restaurant, catalog
        self.cart, self.baseline, self.products = cart, baseline, products
        self.demand = Counter({x['product_id']: x['quantity'] for x in cart})
        self.unit = {x['product_id']: x['unit_price'] for x in baseline['items']}
        self.partial, self.reasons, self.max_offers = False, set(), 0
        self.actual_ids = {}
        self.deadline = time.monotonic() + 70

    def available(self, pid):
        return pid in self.catalog

    def match(self, pid, remaining):
        if remaining[pid] > 0:
            return pid
        alias = ALIASES.get(pid)
        if alias:
            target, source_name, target_name, weight = alias
            a, b = self.menu.catalog_records.get(pid, {}), self.menu.catalog_records.get(target, {})
            if (remaining[target] > 0 and a.get('name') == source_name and b.get('name') == target_name and
                    (weight is None or b.get('weight') == weight)):
                return target
        return None

    def child(self, pid, qty, group):
        values = self.menu.standard_products([{'product_id': pid, 'quantity': qty}],
                                            self.catalog, self.restaurant['pickup'], as_composition=True)
        if len(values) != 1:
            raise protocol()
        return {**values[0], 'groupId': group}

    def edge(self, edge):
        edge = obj(edge)
        pid, qty = edge.get('product_id'), edge.get('default_qty')
        lo, hi, group = edge.get('min_qty'), edge.get('max_qty'), edge.get('group_id')
        if (type(pid) is not int or any(type(x) is not int for x in (qty, lo, hi)) or
                not 0 <= lo <= qty <= hi <= 100 or not isinstance(group, str) or not group):
            raise protocol()
        return pid, qty, group

    def bundles(self):
        candidates = []
        for pid in sorted(self.catalog):
            if self.catalog[pid]['product_class'] != 'VALUE_MEAL':
                continue
            try:
                source = self.menu.price_records[str(pid)]
                remaining, composition = self.demand.copy(), []
                estimate = number(source.get('price'))
                for edge in array(source.get('composition', [])):
                    child, qty, group = self.edge(edge)
                    if qty == 0:
                        continue
                    record = self.menu.price_records.get(str(child), {})
                    if record.get('product_class') == 'RAW_ITEM':
                        continue
                    logical = self.match(child, remaining)
                    if not self.available(child) or logical is None or remaining[logical] < qty:
                        raise protocol()
                    remaining[logical] -= qty
                    composition.append(self.child(child, qty, group))
                    if edge.get('cost_inclusive') is not True:
                        estimate += number(record.get('price')) * qty
                states = [(remaining, [], estimate)]
                for edge in array(source.get('choices', [])):
                    group_id, qty, group = self.edge(edge)
                    if qty == 0:
                        continue
                    node = self.menu.price_records.get(str(group_id), {})
                    if (node.get('product_class') != 'CHOICE' or node.get('choices') or
                            node.get('outage') is not False or 'PICKUP' not in node.get('dist_points', []) or qty > 4):
                        raise protocol()
                    next_states = []
                    for remaining, choices, estimate in states:
                        slots = [(remaining, [], estimate)]
                        for _ in range(qty):
                            options = []
                            for left, selected, cost in slots:
                                for option in array(node.get('composition', [])):
                                    child = option.get('product_id')
                                    if (not self.available(child) or option.get('group_id') != group or
                                            type(option.get('max_qty')) is not int or option['max_qty'] < 1):
                                        continue
                                    logical = self.match(child, left)
                                    placeholder = (child in PLACEHOLDERS and
                                        self.menu.catalog_records.get(child, {}).get('name') == PLACEHOLDERS[child] and
                                        self.menu.price_records[str(child)].get('price') == 0)
                                    if logical is None and not placeholder:
                                        continue
                                    rem = left.copy()
                                    if logical is not None:
                                        rem[logical] -= 1
                                    selection = selected + [self.child(child, 1, group)]
                                    extra = 0 if option.get('cost_inclusive') is True else number(
                                        self.menu.price_records[str(child)].get('price'))
                                    options.append((rem, selection, cost + extra))
                            # Four distinct exact-content alternatives per group.
                            slots = options[:4]
                        for left, selected, cost in slots:
                            merged = {}
                            for child in selected:
                                key = child['productId']
                                if key in merged:
                                    merged[key]['qty'] += child['qty']
                                else:
                                    merged[key] = deepcopy(child)
                            wrapper = {'productId': group_id, 'qty': qty, 'type': 'product',
                                       'productClass': 'CHOICE', 'groupId': group,
                                       'composition': list(merged.values())}
                            next_states.append((left, choices + [wrapper], cost))
                    states = next_states[:4]
                for left, choices, estimate in states:
                    consumed = self.demand - left
                    if not consumed:
                        continue
                    product = {'productId': pid, 'qty': 1, 'type': 'product', 'productClass': 'VALUE_MEAL'}
                    if composition:
                        product['composition'] = composition
                    if choices:
                        product['choices'] = choices
                    candidates.append({'key': ('bundle', pid), 'name': self.catalog[pid]['name'],
                                       'kind': 'bundle', 'demand': consumed, 'estimate': estimate,
                                       'product': product, 'threshold': 0})
            except Failure:
                continue  # Unsupported menu trees never become flat order products.
        return candidates

    def promotions(self):
        rid = self.restaurant.get('catalog_id')
        if not isinstance(rid, str) or not rid:
            self.partial = True
            self.reasons.add('restaurant_catalog_id_missing')
            return []
        answer = self.auth.request('GET', OFFERS, city_id=self.restaurant['city_id'],
                                   params={'restaurant': rid, 'channel_type': 20})
        if answer.get('needPersonalFilter'):
            self.partial = True
            self.reasons.add('personal_filter_unsupported')
            return []
        offers = [obj(o) for section in array(answer.get('sections') or [])
                  for o in array(obj(section).get('offers') or [])]
        eligible = []
        for offer in offers:
            if (offer.get('type') != 'food' or offer.get('isActive') is not True or offer.get('award') or
                    20 not in array(offer.get('channelType') or []) or offer.get('requirements') or
                    offer.get('punch') or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', str(offer.get('id', '')))):
                continue
            if offer.get('secondsLeft') is not None and number(offer['secondsLeft']) <= 0:
                continue
            if offer.get('availableInRestaurants') and rid not in offer['availableInRestaurants']:
                continue
            try:
                remaining, products, chosen = self.demand.copy(), [], []
                sets = obj(offer.get('productSets'))
                if not sets or len(sets) > 10:
                    continue
                for _, options in sorted(sets.items()):
                    found = None
                    for option in array(options):
                        child, qty = option.get('sku'), option.get('qty')
                        if (type(child) is int and type(qty) is int and 0 < qty <= remaining[child] and
                                self.available(child)):
                            found = (child, qty)
                            break
                    if found is None:
                        raise protocol()
                    child, qty = found
                    remaining[child] -= qty
                    products.extend(self.menu.standard_products([{'product_id': child, 'quantity': qty}],
                                    self.catalog, self.restaurant['pickup']))
                    chosen.append(child)
                if len(set(chosen)) != len(chosen):
                    continue
                threshold = number(offer.get('minimalAmount') or 0)
                estimate = number(offer.get('totalPrice'))
                consumed = self.demand - remaining
                if threshold > sum(self.unit[k] * n for k, n in remaining.items()):
                    continue
                eligible.append({'key': ('promotion', offer['id']), 'kind': 'promotion',
                                 'name': str(offer.get('title') or 'Акция')[:200], 'demand': consumed,
                                 'estimate': estimate, 'products': products, 'chosen': chosen,
                                 'threshold': threshold})
            except Failure:
                continue
        if eligible:
            config = self.auth.request('GET', ORDER_CONFIG, city_id=self.restaurant['city_id'])
            maximum = config.get('max_offers_in_order')
            self.max_offers = min(maximum, 8) if type(maximum) is int and maximum >= 0 else 1
        return eligible

    def plans(self, candidates):
        candidates = [c for c in candidates if c['estimate'] < sum(self.unit[k] * n for k, n in c['demand'].items())]
        candidates.sort(key=lambda c: (c['estimate'] - sum(self.unit[k] * n for k, n in c['demand'].items()), c['key']))
        if len(candidates) > MAX_CANDIDATES:
            self.partial = True
            self.reasons.add('candidate_limit')
        candidates = candidates[:MAX_CANDIDATES]
        initial = (self.demand.copy(), [], self.baseline['total'])
        beam, singles = [initial], []
        for candidate in candidates:
            states = list(beam)
            for remaining, picks, cost in beam:
                if any(c['key'] == candidate['key'] for c, _ in picks):
                    continue
                if candidate['kind'] == 'promotion' and sum(c['kind'] == 'promotion' for c, _ in picks) >= self.max_offers:
                    continue
                fit = min(remaining[k] // n for k, n in candidate['demand'].items())
                if candidate['kind'] == 'promotion':
                    fit = min(fit, 1)
                for count in sorted({1, fit}):
                    if count <= 0 or count > fit:
                        continue
                    left = remaining.copy()
                    for k, n in candidate['demand'].items():
                        left[k] -= n * count
                    new_cost = cost + count * (candidate['estimate'] - sum(self.unit[k] * n for k, n in candidate['demand'].items()))
                    state = (left, picks + [(candidate, count)], new_cost)
                    states.append(state)
                    if not picks:
                        singles.append(state)
            states.sort(key=lambda s: (s[2], repr([(c['key'], n) for c, n in s[1]])))
            beam = [initial] + [s for s in states if s[1]][:MAX_PLANS]
        # Try the best combined plan, then single deals as fallbacks for non-stacking promotions.
        ranked = sorted([s for s in beam if s[1]], key=lambda s: s[2])
        singles.sort(key=lambda s: s[2])
        scheduled = ranked[:1] + singles[:2] + ranked[1:]
        unique, result = set(), []
        for state in scheduled:
            key = repr([(c['key'], n, c.get('product'), c.get('chosen')) for c, n in state[1]])
            if key not in unique:
                unique.add(key)
                result.append(state)
        return result

    def payload(self, state):
        remaining, picks, _ = state
        products = self.menu.standard_products([{'product_id': k, 'quantity': n} for k, n in sorted(remaining.items()) if n],
                                               self.catalog, self.restaurant['pickup'])
        specials, descriptions = [], []
        for candidate, qty in picks:
            if candidate['kind'] == 'bundle':
                products.append({**deepcopy(candidate['product']), 'qty': qty})
            else:
                offer_id = candidate['key'][1]
                if offer_id not in self.actual_ids:
                    answer = self.auth.request('GET', actual_offer_path(offer_id), city_id=self.restaurant['city_id'],
                        params={'restaurant': self.restaurant['catalog_id'], 'channel_type': 20})
                    actual = answer.get('id')
                    if not isinstance(actual, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', actual):
                        raise protocol()
                    self.actual_ids[offer_id] = actual
                specials.append({'offerId': self.actual_ids[offer_id], 'products': candidate['products'],
                                 'chosen': candidate['chosen'], 'service': 1, 'award': False})
            descriptions.append({'kind': candidate['kind'], 'name': candidate['name'], 'quantity': qty,
                'items': [{'product_id': k, 'quantity': n * qty} for k, n in sorted(candidate['demand'].items())]})
        if len({p['productId'] for p in products}) != len(products):
            raise protocol()
        return products, specials, descriptions

    def run(self):
        candidates = self.bundles()
        try:
            candidates += self.promotions()
        except Failure as error:
            if error.code not in OPTIONAL_FAILURES:
                raise
            self.partial = True
            self.reasons.add('promotions_unavailable')
        plans = self.plans(candidates)
        best, products, specials, applied = self.baseline, self.products, [], []
        attempts, quotes = 0, 0
        for state in plans:
            if attempts >= MAX_QUOTES or time.monotonic() >= self.deadline:
                self.partial = True
                self.reasons.add('quote_limit')
                break
            attempts += 1
            try:
                proposed, promo, descriptions = self.payload(state)
                if time.monotonic() >= self.deadline:
                    self.partial = True
                    self.reasons.add('quote_limit')
                    break
                body = {'storeId': str(self.restaurant['restaurant_id']), 'wayOfGet': self.menu.pickup_code(self.restaurant),
                        'products': proposed, 'spProducts': promo, 'calculateLoyalty': False}
                quotes += 1
                answer = self.auth.request('POST', TOTAL, city_id=self.restaurant['city_id'], json=body)
                result = checked_quote(answer, proposed, promo)
                threshold = max(c['threshold'] for c, _ in state[1])
                if not result['confirmed']:
                    self.partial = True
                    self.reasons.add('candidate_not_confirmed')
                if result['confirmed'] and threshold <= result['total'] < best['total']:
                    best, products, specials, applied = result, proposed, promo, descriptions
            except Failure as error:
                if error.code not in OPTIONAL_FAILURES:
                    raise
                self.partial = True
                self.reasons.add('candidate_rejected')
        return best, products, specials, applied, {'status': 'partial' if self.partial else 'checked',
            'additional_quotes': quotes, 'reasons': sorted(self.reasons), 'globally_optimal': False}
