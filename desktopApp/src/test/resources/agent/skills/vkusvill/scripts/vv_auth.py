"""Phone login and token refresh; called while holding the user-store lock."""
import base64
import json
import time
import uuid

from vv_common import Failure, iso, obj, protocol, text
from vv_client import USER_AGENT


def jwt_expiry(token):
    try:
        part = token.split(".")[1]
        value = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))["exp"]
        return value if type(value) in (int, float) else None
    except (ValueError, KeyError, IndexError, TypeError):
        return None


class Auth:
    def __init__(self, store, client, clock=time.time):
        self.store = store
        self.client = client
        self.clock = clock

    def status(self):
        pending = self.store.get("pending")
        if pending and pending["expires"] <= self.clock():
            self.store.put("pending", None)
            pending = None
        credentials = self.store.get("credentials")
        state = "reauth_required" if self.store.get("reauth") else "unauthenticated"
        if credentials and not self.store.get("reauth"):
            state = "authenticated"
        if pending:
            state = "pending_sms"
        return {
            "state": state,
            "phone_masked": (pending or credentials or {}).get("phone_masked"),
            "account_number": credentials["number"] if credentials else None,
            "auth_session_id": pending["id"] if pending else None,
            "local_session_expires_at": iso(pending["expires"]) if pending else None,
            "resend_available_at": iso(pending["resend_at"]) if pending else None,
            "capabilities": {"scheduler": False},
        }

    def challenge(self, pending):
        return {
            "auth_session_id": pending["id"], "phone_masked": pending["phone_masked"],
            "delivery_status": pending["delivery_status"],
            "local_session_expires_at": iso(pending["expires"]),
            "provider_code_expires_at": None,
            "resend_available_at": iso(pending["resend_at"]),
            "local_attempts_remaining": max(0, 5 - pending["attempts"]),
            "provider_attempts_remaining": None,
        }

    def start(self, args):
        if self.status()["state"] == "authenticated":
            raise Failure("ALREADY_AUTHENTICATED", "Сначала явно выйдите для смены аккаунта.")
        now = self.clock()
        # User-wide limits deliberately survive cancelled and replaced challenges.
        sends = [ts for ts in self.store.get("sms_sends", []) if now - ts < 900]
        if sends and (now - sends[-1] < 60 or len(sends) >= 3):
            wait = max(60 - (now - sends[-1]), 900 - (now - sends[0]) if len(sends) >= 3 else 0)
            raise Failure("RATE_LIMITED", "Дождитесь разрешённого времени новой SMS.",
                          retry_after=max(1, int(wait) + 1))
        self.store.update({"sms_sends": sends + [now], "pending": None})
        device_id = str(uuid.uuid4())
        guest = self.client.request("POST", "/api/guest/createAnonymousCard/",
                                    files={"device_id": (None, device_id)})
        pending = {
            "id": str(uuid.uuid4()), "device_id": device_id, "number": text(guest.get("number")),
            "token": text(guest.get("token")), "phone": args["phone"],
            "phone_masked": "+7******" + args["phone"][-4:],
            "expires": now + 600, "resend_at": now + 60, "attempts": 0,
            "delivery_status": "unknown",
        }
        self.store.put("pending", pending)
        try:
            answer = self.client.request("POST", "/api/user/",
                headers=self.guest_headers(pending), files=self.form(pending))
            if type(answer.get("status")) is not int or answer["status"] != 1:
                raise protocol()
            pending["delivery_status"] = "requested"
            self.store.put("pending", pending)
        except Failure as failure:
            if failure.code not in ("OPERATION_OUTCOME_UNKNOWN", "UPSTREAM_PROTOCOL_ERROR"):
                self.store.put("pending", None)
                raise
        return self.challenge(pending)

    @staticmethod
    def guest_headers(pending):
        return {"X-VKUSVILL-TOKEN": pending["token"], "X-VKUSVILL-DEVICE": "android"}

    @staticmethod
    def form(pending):
        return {"number": (None, pending["number"]), "Phone_number": (None, pending["phone"])}

    def pending(self, session_id):
        self.status()
        pending = self.store.get("pending")
        if not pending or pending["id"] != session_id:
            raise Failure("AUTH_SESSION_EXPIRED", "Попытка входа отсутствует или истекла.")
        return pending

    def submit(self, args):
        pending = self.pending(args["auth_session_id"])
        if pending.get("verification_unknown"):
            raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход проверки кода неизвестен; начните новый вход.")
        if pending["attempts"] >= 5:
            raise Failure("RATE_LIMITED", "Достигнут локальный лимит проверок кода.")
        pending["attempts"] += 1
        pending["verification_unknown"] = True
        self.store.put("pending", pending)
        fields = self.form(pending)
        fields.update({"sms": (None, args["code"]), "is_news_accepted": (None, "0")})
        try:
            answer = self.client.request("POST", "/api/user/checkSms/", params={"source": 2},
                                        headers=self.guest_headers(pending), files=fields)
            credentials = {key: text(answer.get(key))
                           for key in ("number", "token", "access_token", "refresh_token")}
        except Failure as failure:
            if failure.code in ("AUTH_EXPIRED", "ACCESS_DENIED", "RATE_LIMITED"):
                pending["verification_unknown"] = False
                self.store.put("pending", pending)
            # Unknown JSON/error formats do not invent an SMS_CODE_INVALID classification.
            raise
        credentials["phone_masked"] = pending["phone_masked"]
        self.store.update({"credentials": credentials, "pending": None, "reauth": None,
                           "refresh_unknown": None, "epoch": str(uuid.uuid4())})
        return self.status()

    def cancel(self, args):
        pending = self.store.get("pending")
        if pending and pending["id"] == args["auth_session_id"]:
            self.store.put("pending", None)
        return {"cancelled": True}

    def logout(self):
        self.store.update({"credentials": None, "pending": None, "reauth": None,
                           "refresh_unknown": None, "epoch": str(uuid.uuid4())})
        return self.status()

    def credentials(self):
        credentials = self.store.get("credentials")
        if not credentials or self.store.get("reauth"):
            raise Failure("AUTH_REQUIRED", "Нужен вход во ВкусВилл по телефону и SMS.")
        return credentials

    @staticmethod
    def headers(credentials, access=True):
        result = {"X-VKUSVILL-TOKEN": credentials["token"], "X-VKUSVILL-DEVICE": "android",
                  "X-VKUSVILL-VERSION": "4.12.11 (412011)", "User-Agent": USER_AGENT}
        if access:
            result["X-VKUSVILL-TOKEN-ACCESS"] = credentials["access_token"]
        return result

    def refresh(self):
        credentials = self.credentials()
        if self.store.get("refresh_unknown"):
            raise Failure("OPERATION_OUTCOME_UNKNOWN", "Исход обновления сессии неизвестен; нужен новый вход.")
        # Persist before sending: a killed process must not replay a rotating refresh token.
        self.store.put("refresh_unknown", True)
        try:
            answer = self.client.request("POST", "/api/user/v1/refreshToken",
                params={"number": credentials["number"]}, headers=self.headers(credentials, False),
                data={"refresh_token": credentials["refresh_token"]})
            data = obj(answer.get("data"))
            updated = dict(credentials, access_token=text(data.get("access_token")))
            for key in ("token", "refresh_token"):
                if key in data:
                    updated[key] = text(data[key])
            self.store.update({"credentials": updated, "refresh_unknown": None})
        except Failure as failure:
            if failure.code == "AUTH_EXPIRED":
                self.store.update({"reauth": True, "refresh_unknown": None})
            elif failure.code in ("ACCESS_DENIED", "RATE_LIMITED"):
                self.store.put("refresh_unknown", None)
            raise

    def request(self, method, path, **kwargs):
        credentials = self.credentials()
        expiry = jwt_expiry(credentials["access_token"])
        if expiry is not None and expiry - self.clock() <= 300:
            self.refresh()
            credentials = self.credentials()
        params = {"number": credentials["number"], **kwargs.pop("params", {})}
        try:
            return self.client.request(method, path, headers=self.headers(credentials), params=params, **kwargs)
        except Failure as failure:
            if failure.code != "AUTH_EXPIRED" or method != "GET":
                raise
            self.refresh()
            try:
                return self.client.request(method, path, headers=self.headers(self.credentials()),
                                           params=params, **kwargs)
            except Failure as repeated:
                if repeated.code == "AUTH_EXPIRED":
                    self.store.put("reauth", True)
                raise
