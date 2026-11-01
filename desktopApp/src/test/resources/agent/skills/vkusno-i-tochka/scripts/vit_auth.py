"""Phone login, persistent device identity and guarded token rotation."""
import base64
import json
import secrets
import time
import uuid

from vit_client import LOGIN, CONFIRM, REFRESH
from vit_common import Failure, iso, protocol, text


def jwt_expiry(token):
    try:
        part = token.split(".")[1]
        value = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))["exp"]
        return value if type(value) in (int, float) else None
    except (ValueError, KeyError, IndexError, TypeError):
        return None


class Auth:
    def __init__(self, store, client, clock=time.time):
        self.store, self.client, self.clock = store, client, clock

    def status(self):
        pending = self.store.get("pending")
        if pending and pending["expires"] <= self.clock():
            self.store.put("pending", None)
            pending = None
        credentials = self.store.get("credentials")
        state = "authenticated" if credentials else "unauthenticated"
        if self.store.get("reauth") or self.store.get("refresh_unknown"):
            state = "reauth_required"
        if pending:
            state = "pending_sms"
        return {"state": state, "phone_masked": (pending or credentials or {}).get("phone_masked"),
                "auth_session_id": pending["id"] if pending else None,
                "local_session_expires_at": iso(pending["expires"]) if pending else None,
                "delivery_status": pending.get("delivery_status") if pending else None,
                "capabilities": {"app_cart_transfer": False, "checkout": True, "payment": "one_time_card_link",
                                 "order_status": True, "sbp": False, "order_cancel": False}}

    def headers(self, access=False, city_id=None):
        device = self.store.get("device_id")
        if not device:
            device = secrets.token_hex(8)
            self.store.put("device_id", device)
        result = {"X-Device-ID": device, "X-Platform": "Android", "X-OS-Version": "33",
                  "X-App-Version": "15.0.0", "X-Build-Number": "143206", "X-Language": "ru_RU",
                  "X-Device-Model": "Souz", "X-Services-Provider": "google", "X-Cellular-Name": "",
                  "X-Timezone": "GMT+03:00", "X-Request-ID": str(uuid.uuid4())}
        if city_id:
            result["X-City-ID"] = city_id
        if access:
            result["Authorization"] = "Bearer " + self.credentials()["access_token"]
        return result

    def start(self, args):
        if self.store.get("credentials"):
            raise Failure("ALREADY_AUTHENTICATED", "Для смены аккаунта сначала явно выйдите.")
        now = self.clock()
        sends = [value for value in self.store.get("sms_sends", []) if now - value < 900]
        if sends and (now - sends[-1] < 60 or len(sends) >= 3):
            delay = max(60 - (now - sends[-1]), 900 - (now - sends[0]) if len(sends) >= 3 else 0)
            raise Failure("RATE_LIMITED", "Дождитесь разрешённого времени новой SMS.",
                          retry_after=max(1, int(delay) + 1))
        pending = {"id": str(uuid.uuid4()), "phone_masked": "+7******" + args["phone"][-4:],
                   "expires": now + 600, "attempts": 0, "delivery_status": "unknown"}
        self.store.update({"sms_sends": sends + [now], "pending": pending})
        try:
            answer = self.client.request("POST", LOGIN, headers=self.headers(),
                                         json={"phone": args["phone"]})
            pending["ticket"] = text(answer.get("ticket"))
            pending["delivery_status"] = "requested"
            self.store.put("pending", pending)
        except Failure as failure:
            if failure.code not in ("OPERATION_OUTCOME_UNKNOWN", "UPSTREAM_PROTOCOL_ERROR"):
                self.store.put("pending", None)
            raise
        return {**self.status(), "local_attempts_remaining": 5,
                "resend_available_at": iso(now + 60), "provider_code_expires_at": None}

    def pending(self, session_id):
        self.status()
        pending = self.store.get("pending")
        if not pending or pending["id"] != session_id:
            raise Failure("AUTH_SESSION_EXPIRED", "Попытка входа отсутствует или истекла.")
        return pending

    def submit(self, args):
        pending = self.pending(args["auth_session_id"])
        if not pending.get("ticket") or pending.get("verification_unknown"):
            raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход предыдущего шага входа неизвестен.")
        if pending["attempts"] >= 5:
            raise Failure("RATE_LIMITED", "Достигнут локальный лимит проверок кода.")
        pending["attempts"] += 1
        pending["verification_unknown"] = True
        self.store.put("pending", pending)
        try:
            answer = self.client.request("POST", CONFIRM, headers=self.headers(),
                                         json={"ticket": pending["ticket"], "code": args["code"]})
            credentials = {key: text(answer.get(key)) for key in ("access_token", "refresh_token")}
            credentials["phone_masked"] = pending["phone_masked"]
        except Failure as failure:
            if failure.code in ("REQUEST_REJECTED", "AUTH_EXPIRED", "ACCESS_DENIED", "RATE_LIMITED"):
                pending["verification_unknown"] = False
                self.store.put("pending", pending)
            raise
        self.store.update({"credentials": credentials, "pending": None, "reauth": None,
                           "refresh_unknown": None, "quote": None, "epoch": str(uuid.uuid4())})
        return self.status()

    def cancel(self, args):
        pending = self.store.get("pending")
        if pending and pending["id"] == args["auth_session_id"]:
            self.store.put("pending", None)
        return {"cancelled": True}

    def logout(self):
        self.store.update({"credentials": None, "pending": None, "reauth": None,
                           "refresh_unknown": None, "quote": None, "epoch": str(uuid.uuid4())})
        return self.status()

    def credentials(self):
        if self.store.get("refresh_unknown"):
            raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход обновления сессии неизвестен; нужен новый вход.")
        credentials = self.store.get("credentials")
        if not credentials or self.store.get("reauth"):
            raise Failure("AUTH_REQUIRED", "Нужен вход по телефону и SMS.")
        return credentials

    def refresh(self):
        credentials = self.credentials()
        self.store.put("refresh_unknown", True)
        # Build the header directly: credentials() intentionally blocks uncertain refreshes.
        headers = self.headers()
        headers["Authorization"] = "Bearer " + credentials["access_token"]
        try:
            answer = self.client.request("POST", REFRESH, headers=headers,
                                         json={"refresh_token": credentials["refresh_token"]})
            updated = dict(credentials, access_token=text(answer.get("access_token")))
            if "refresh_token" in answer:
                updated["refresh_token"] = text(answer["refresh_token"])
            self.store.update({"credentials": updated, "refresh_unknown": None})
        except Failure as failure:
            if failure.code == "AUTH_EXPIRED":
                self.store.update({"reauth": True, "refresh_unknown": None})
            elif failure.code in ("REQUEST_REJECTED", "ACCESS_DENIED", "RATE_LIMITED"):
                self.store.put("refresh_unknown", None)
            raise

    def request(self, method, path, city_id=None, retry_auth=True, **kwargs):
        expiry = jwt_expiry(self.credentials()["access_token"])
        if expiry is not None and expiry - self.clock() <= 300:
            self.refresh()
        try:
            return self.client.request(method, path, headers=self.headers(True, city_id), **kwargs)
        except Failure as failure:
            if failure.code != "AUTH_EXPIRED" or not retry_auth:
                raise
            self.refresh()
            try:
                return self.client.request(method, path, headers=self.headers(True, city_id), **kwargs)
            except Failure as repeated:
                if repeated.code == "AUTH_EXPIRED":
                    self.store.put("reauth", True)
                raise
