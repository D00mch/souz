"""One-time card checkout and read-only order status; never visits payment URLs."""
import re
import uuid
from urllib.parse import urlsplit

from vit_client import CREATE, ORDER, PAYMENT_METHODS
from vit_common import Failure, array, iso, number, obj, protocol, text
from vit_menu import PICKUPS


COLORS = dict(zip(('white', 'gray', 'black', 'brown', 'beige', 'red', 'yellow', 'green', 'blue', 'purple'),
                  ('БЕЛЫЙ', 'СЕРЫЙ', 'ЧЕРНЫ', 'КОРИЧ', 'БЕЖЕВ', 'КРАСН', 'ЖЕЛТЫ', 'ЗЕЛЕН', 'СИНИЙ', 'ФИОЛЕ')))
STATUSES = {10: 'NEW', 20: 'PAY_HOLD_IN_PROCESS', 30: 'PAY_HELD', 40: 'PAY_HOLD_ERROR',
            45: 'ORDER_CANCELED', 50: 'PAY_HOLD_TIMEOUT', 60: 'WAIT_FOR_GET', 65: 'WAIT_FOR_CALL',
            70: 'WAIT_FOR_PAY_COMPLETE', 72: 'PREPARING', 73: 'READY_FOR_PICKUP', 74: 'ORDER_RECEIVED',
            75: 'PAY_COMPLETE_ERROR', 80: 'PAY_COMPLETE', 90: 'COMPLETE_OTHER', 95: 'COMPLETE_MCAUTO',
            100: 'REFUNDED', 110: 'SEND_ERROR', 115: 'FOE_VALIDATE_ERROR', 120: 'MENU_CHANGED_CANCEL',
            130: 'WAIT_FOR_HOLD_CANCEL', 135: 'PAID_HOLD_CANCEL_ERROR', 140: 'PAID_HOLD_CANCELLED',
            150: 'REMOVED_BY_CLEARING_CYCLE', 270: 'WAIT_FOR_PAY_COMPLETE_REFUND',
            280: 'PAY_COMPLETE_REFUND', 290: 'PAY_COMPLETE_REFUND_ERROR', 300: 'SEND_TO_CASHIER',
            330: 'PAY_COMPLETE_PARTIAL_REFUND'}
TERMINAL = {45, 50, 90, 95, 100, 120, 140, 150, 280}


def identifier(value):
    return isinstance(value, str) and bool(re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value))


def payment_url(value):
    if value is None or value == '':
        return None
    try:
        uri = urlsplit(value)
        if (not isinstance(value, str) or len(value) > 4096 or
                any(c.isspace() or ord(c) < 32 for c in value) or uri.scheme != 'https' or
                not uri.hostname or '.' not in uri.hostname or uri.username or uri.password or
                uri.port not in (None, 443)):
            return None
        return value
    except (ValueError, TypeError, AttributeError):
        return None


def optional_string(value):
    if value is None:
        return None
    value = text(value)
    if len(value) > 256:
        raise protocol()
    return value


def status_code(value):
    if type(value) is int and value >= 0:
        return value
    if isinstance(value, str):
        if value.isdigit() and len(value) <= 8:
            return int(value)
        return next((code for code, name in STATUSES.items() if name == value), None)
    return None


def timestamp(value):
    if value is None:
        return None
    if type(value) not in (int, float) or not 0 <= value <= 253402300799:
        raise protocol()
    return iso(value)


class Orders:
    def __init__(self, store, auth, menu):
        self.store, self.auth, self.menu, self.clock = store, auth, menu, auth.clock

    def draft_key(self):
        return self.store.fingerprint({'selected': self.store.get('selected'),
                                       'cart': self.store.get('cart'),
                                       'revision': self.store.get('revision', 0)})

    def check_guard(self):
        guard = self.store.get('order_creation_guard')
        if guard:
            raise Failure('ORDER_IN_PROGRESS' if guard.get('order_id') else 'OPERATION_OUTCOME_UNKNOWN',
                          'Предыдущее оформление не завершено; новый заказ не отправлен.',
                          details={'order_id': guard.get('order_id'), 'stage': 'order_creation'})

    def pickup_fields(self, restaurant, args):
        pickup = restaurant['pickup']
        if pickup not in ('auto', 'parking', 'takeaway', 'eat_in'):
            raise Failure('CHECKOUT_PICKUP_UNSUPPORTED', 'Оформление поддерживает Авто, парковку и прилавок.')
        fields = {'parking_zone', 'vehicle_plate', 'vehicle_color'} & args.keys()
        if pickup != 'parking':
            if fields:
                raise Failure('INVALID_ARGUMENT', 'Параметры машины применяются только к парковке.')
            return {}
        if fields != {'parking_zone', 'vehicle_plate', 'vehicle_color'}:
            raise Failure('PARKING_DETAILS_REQUIRED', 'Нужны зона парковки, госномер и цвет машины.')
        bounds = restaurant.get('parking_zone_range')
        if not bounds or not bounds['min'] <= args['parking_zone'] <= bounds['max']:
            raise Failure('INVALID_ARGUMENT', 'Зона вне диапазона выбранного ресторана.')
        return {'carPlate': str(args['parking_zone']) + '-' + args['vehicle_plate'][1:4] +
                COLORS[args['vehicle_color']]}

    def card_method(self, restaurant):
        answer = self.auth.request('GET', PAYMENT_METHODS, city_id=restaurant['city_id'],
                                   params={'store_id': str(restaurant['restaurant_id']), 'sale_channel': 'mop'})
        for method in array(answer.get('payment_methods')):
            method = obj(method)
            if method.get('payment_method') == 'card' and method.get('payment_service') in (
                    'sber_direct', 'sber_legacy', 'yookassa'):
                return {'method': 'card', 'service': method['payment_service']}
        raise Failure('CARD_PAYMENT_UNAVAILABLE', 'Сервис не предложил одноразовую оплату картой.')

    def prepare(self, args):
        self.auth.credentials()
        self.check_guard()
        self.store.put('checkout', None)
        calculated = self.menu.calculate()
        quote, restaurant = calculated['quote'], calculated['restaurant_details']
        if not quote['confirmed']:
            raise Failure('QUOTE_NOT_CONFIRMED', 'Сервер не подтвердил полный состав корзины.')
        fields = self.pickup_fields(restaurant, args)
        method = self.card_method(restaurant)
        checkout = {'checkout_id': str(uuid.uuid4()), 'epoch': self.store.get('epoch'),
                    'draft_key': self.draft_key(), 'expires': self.clock() + 180,
                    'total': quote['total'], 'restaurant': restaurant, 'items': calculated['items'],
                    'parking': {key: args[key] for key in ('parking_zone', 'vehicle_plate', 'vehicle_color')
                                if key in args},
                    'payment_service': method['service'],
                    'body': {'storeId': str(restaurant['restaurant_id']),
                             'wayOfGet': PICKUPS[restaurant['pickup']][1],
                             'products': self.store.get('quoted_products'),
                             'spProducts': self.store.get('quoted_sp_products') or [], 'redeemBonuses': 0, **fields,
                             'paymentData': {'method': 'card', 'email': args['email'],
                                             'mailing': False, 'savePaymentMethod': False}}}
        self.store.put('checkout', checkout)
        return {'checkout_id': checkout['checkout_id'], 'expires_at': iso(checkout['expires']),
                'total': checkout['total'], 'currency': 'RUB', 'restaurant': restaurant,
                'items': calculated['items'], 'payment_method': 'card', 'creates_order': False,
                'original_total': quote['original_total'], 'savings': quote['savings'],
                'applied_deals': quote['applied_deals'], 'optimization': quote['optimization'],
                'receipt_email': args['email'],
                'parking': {key: args[key] for key in ('parking_zone', 'vehicle_plate', 'vehicle_color')
                            if key in args}}

    def create(self, args):
        self.auth.credentials()
        self.check_guard()
        checkout = self.store.get('checkout')
        if (not checkout or checkout['checkout_id'] != args['checkout_id'] or
                checkout['epoch'] != self.store.get('epoch') or checkout.get('consumed') or
                checkout['expires'] <= self.clock() or checkout['draft_key'] != self.draft_key()):
            raise Failure('CHECKOUT_EXPIRED', 'Подготовьте оформление заново и проверьте состав и сумму.')
        calculated = self.menu.calculate()
        if (not calculated['quote']['confirmed'] or calculated['quote']['total'] != checkout['total'] or
                calculated['items'] != checkout['items'] or
                self.store.get('quoted_products') != checkout['body']['products'] or
                (self.store.get('quoted_sp_products') or []) != checkout['body']['spProducts']):
            self.store.put('checkout', None)
            raise Failure('CHECKOUT_CHANGED', 'Состав или сумма изменились; требуется новое оформление.',
                          details={'total': calculated['quote']['total'], 'currency': 'RUB'})
        restaurant = calculated['restaurant_details']
        self.pickup_fields(restaurant, checkout['parking'])
        method = self.card_method(restaurant)
        if method['service'] != checkout['payment_service']:
            self.store.put('checkout', None)
            raise Failure('CHECKOUT_CHANGED', 'Платёжный провайдер изменился; подготовьте оформление заново.')
        if checkout['expires'] <= self.clock():
            raise Failure('CHECKOUT_EXPIRED', 'Подготовка истекла во время проверки; подготовьте оформление заново.')
        # Both records survive process crashes, new request IDs, and logout/login.
        checkout['consumed'] = True
        guard = {'request_id': args['request_id'], 'order_id': None, 'state': 'unknown',
                 'created_at': iso(self.clock())}
        self.store.update({'checkout': checkout, 'order_creation_guard': guard})
        try:
            answer = self.auth.request('POST', CREATE, city_id=restaurant['city_id'],
                                       retry_auth=False, json=checkout['body'])
        except Failure as failure:
            if failure.code in ('AUTH_EXPIRED', 'ACCESS_DENIED', 'RATE_LIMITED', 'REQUEST_REJECTED'):
                self.store.put('order_creation_guard', None)
            raise
        # Persist the ID before parsing remaining fields; malformed success is never reposted.
        if identifier(answer.get('id')):
            guard.update(order_id=answer['id'], state='created', city_id=restaurant['city_id'])
            self.store.put('order_creation_guard', guard)
            self.store.put('last_order', {'order_id': answer['id'], 'version_pos': None,
                                         'city_id': restaurant['city_id']})
        result = self.normalize(answer)
        result['quoted_total'] = checkout['total']
        result['amount_changed'] = result['total'] != checkout['total']
        result['payment_link_available'] = result['payment_url'] is not None
        self.store.put('last_order', {'order_id': result['order_id'], 'version_pos': result['version_pos'],
                                     'city_id': restaurant['city_id']})
        return result

    def normalize(self, source):
        source = obj(source)
        order_id = source.get('id')
        if not identifier(order_id):
            raise protocol()
        code = status_code(source.get('status'))
        version = source.get('versionPos')
        if version is not None and (type(version) is not int or version < 0):
            raise protocol()
        payment = obj(source.get('paymentData') or {})
        details = obj(source.get('statusDetails') or {})
        codes = obj(source.get('codes') or {})
        entries = source.get('statusHistory')
        history = [status_code(obj(entry).get('status')) for entry in array(entries if entries is not None else [])]
        # Current error/cancellation states take precedence over historical success.
        if code in (100, 280, 330):
            payment_state = 'partially_refunded' if code == 330 else 'refunded'
        elif code in (130, 135, 140):
            payment_state = {130: 'hold_release_pending', 135: 'hold_release_error', 140: 'hold_released'}[code]
        elif code in (40, 50, 75, 290):
            payment_state = 'timeout' if code == 50 else 'error'
        elif code == 45:
            payment_state = 'unknown'
        elif code == 80 or (code in (60, 65, 70, 72, 73, 74, 90, 95) and 80 in history):
            payment_state = 'captured'
        elif code == 30 or (code in (60, 65, 70, 72, 73, 74, 90, 95) and 30 in history):
            payment_state = 'held'
        elif code in (10, 20):
            payment_state = 'pending'
        else:
            payment_state = 'unknown'
        terminal = details.get('isTerminal') if type(details.get('isTerminal')) is bool else code in TERMINAL
        url = payment_url(payment.get('url')) if (code in (10, 20, 40) and not terminal and
                                                 payment.get('method') in (None, 'card')) else None
        return {'order_id': order_id, 'version_pos': version, 'status_code': code,
                'status_name': STATUSES.get(code, 'UNKNOWN'), 'payment_state': payment_state,
                'failure_status_names': list(dict.fromkeys(STATUSES[item] for item in history
                    if item in (40, 75, 110, 115, 120, 135, 290))),
                'fulfillment_state': {72: 'preparing', 73: 'ready_for_pickup', 74: 'received',
                                      90: 'completed', 95: 'completed', 45: 'cancelled',
                                      50: 'cancelled', 120: 'cancelled', 140: 'cancelled'}.get(code, 'unknown'),
                'terminal': terminal, 'total': number(source.get('total')), 'currency': 'RUB',
                'payment_url': url, 'payment_method': optional_string(payment.get('method')),
                'pickup_number': optional_string(codes.get('displayNum')) or optional_string(codes.get('shortCode')),
                'payment_deadline': timestamp(payment.get('cancellationTime')),
                'status_deadline': timestamp(details.get('timerExpiredAt')),
                'can_cancel_order': details.get('canCancelOrder') if type(details.get('canCancelOrder')) is bool else None,
                'checked_at': iso(self.clock()),
                'suggested_poll_after_seconds': None if terminal else (5 if code in (10, 20, 30) else 30)}

    def status(self, args):
        self.auth.credentials()
        last = self.store.get('last_order') or {}
        order_id = args.get('order_id') or last.get('order_id')
        if not order_id:
            guard = self.store.get('order_creation_guard')
            raise Failure('OPERATION_OUTCOME_UNKNOWN' if guard else 'ORDER_REQUIRED',
                          'Нет известного ID заказа. Не повторяйте создание при неизвестном исходе.')
        params = {'order_id': order_id}
        if order_id == last.get('order_id') and last.get('version_pos') is not None:
            params['version_pos'] = last['version_pos']
        answer = self.auth.request('GET', ORDER, city_id=last.get('city_id'), params=params)
        if answer.get('id') != order_id:
            raise protocol()
        result = self.normalize(answer)
        guard = self.store.get('order_creation_guard')
        if guard and guard.get('order_id') == order_id and result['terminal']:
            self.store.put('order_creation_guard', None)
        if order_id == last.get('order_id'):
            self.store.put('last_order', dict(last, version_pos=result['version_pos']))
        return result
