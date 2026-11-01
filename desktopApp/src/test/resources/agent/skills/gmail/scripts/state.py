"""Persistent Gmail cursor/pending journal. API calls stay in Souz SafeApiCall."""
import fcntl
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid
from urllib.parse import urlencode

BASE = "https://www.googleapis.com/gmail/v1/users/me"


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,32}", value):
        raise ValueError("Invalid history ID")
    return value


def response(value):
    if value.get("reconnectRequired"):
        return None
    if value.get("statusCode") != 200:
        raise ValueError("Gmail API did not return 200")
    body = value["body"]
    return json.loads(body) if isinstance(body, str) else body


def request(path, **query):
    return {"skillId": "gmail", "method": "GET", "url": BASE + path + ("?" + urlencode(query, doseq=True) if query else "")}


def claimed(state, config, message_id, claim):
    pending = state["pending"].get(message_id)
    if not pending or state["status"] != "ready" or pending["status"] != "claimed" or pending["claim"] != claim:
        raise ValueError("Claim is not active")
    mode = config.get("notificationMode", "signal")
    query = {"format": "full"} if mode == "assistant" else {"format": "metadata", "metadataHeaders": ["From", "Subject", "Date"]}
    return {"status": "claimed", "messageId": message_id, "claim": claim,
            "startedAtMillis": state["startedAtMillis"], "destination": config["destination"],
            "notificationMode": mode,
            "messageRequest": request("/messages/" + message_id, **query)}


def view(state, config):
    cursor = state.get("historyCursor")
    page = state.get("pageToken")
    result = {"status": state["status"], "revision": state["revision"], "historyCursor": cursor,
              "pageToken": page, "watchExpiresAt": state.get("watchExpiresAt", 0),
              "pendingCount": sum(p["status"] == "pending" for p in state["pending"].values()),
              "uncertain": [key for key, p in state["pending"].items() if p["status"] == "uncertain"],
              "profileRequest": request("/profile")}
    if state["status"] == "ready":
        query = {"startHistoryId": cursor, "historyTypes": "messageAdded", "labelId": "INBOX", "maxResults": 25}
        if page:
            query["pageToken"] = page
        result["historyRequest"] = request("/history", **query)
        if state.get("watchExpiresAt", 0) < int(time.time() * 1000) + 86_400_000:
            result["watchRequest"] = {"skillId": "gmail", "method": "POST", "url": BASE + "/watch", "body": json.dumps({
                "topicName": config["topicName"], "labelIds": ["INBOX"], "labelFilterBehavior": "include"})}
    return result


def apply(state, config, operation, data):
    now = time.time()
    for pending in state["pending"].values():
        if pending["status"] == "claimed" and pending["expiresAt"] <= now:
            pending["status"] = "uncertain"  # Delivery may have happened; never silently resend.
    if operation in {"profile", "watch", "history"} and data["response"].get("reconnectRequired"):
        state["status"] = "reconnect_required"
        return {"status": state["status"], "authorizationUrl": data["response"]["authorizationUrl"]}
    if operation == "profile":
        body = response(data["response"])
        if body["emailAddress"].lower() != config["mailboxEmail"].lower():
            state["status"] = "account_mismatch"
        else:
            if state.get("historyCursor") is None:
                state["historyCursor"] = identifier(body["historyId"])
                state["startedAtMillis"] = int(now * 1000)
            state["status"] = "resync_required" if state.get("historyGap") else "ready"
    elif operation == "watch":
        body = response(data["response"])
        # watch's historyId is never a replacement for the processing cursor.
        state["watchExpiresAt"] = max(state.get("watchExpiresAt", 0), int(body["expiration"]))
    elif operation == "history":
        if state["status"] != "ready":
            return {"status": state["status"]}
        if data["cursor"] != state["historyCursor"] or data.get("pageToken") != state.get("pageToken"):
            return {"status": "conflict", "message": "Read status and fetch the current page again."}
        if data["response"].get("statusCode") == 404:
            state.update(status="resync_required", historyGap=True)
        else:
            body = response(data["response"])
            ids = {added["message"]["id"] for history in body.get("history", []) for added in history.get("messagesAdded", [])}
            if any(not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", message_id) for message_id in ids):
                raise ValueError("Invalid message ID")
            ids -= state["pending"].keys() | set(state["done"])
            if len(state["pending"]) + len(ids) > 500:
                return {"status": "backlog_full", "message": "Process pending messages before fetching this page again."}
            state["pending"].update({message_id: {"status": "pending"} for message_id in sorted(ids)})
            state["pageToken"] = body.get("nextPageToken") or None
            if not state["pageToken"]:
                state["historyCursor"] = str(max(int(state["historyCursor"]), int(identifier(body["historyId"]))))
                state["done"] = state["done"][-2000:]
    elif operation == "claim":
        if state["status"] != "ready":
            return {"status": state["status"]}
        for message_id, pending in state["pending"].items():
            if pending["status"] != "pending":
                continue
            claim = str(uuid.uuid4())
            pending.update(status="claimed", claim=claim, expiresAt=now + 600)
            return claimed(state, config, message_id, claim)
        return {"status": "empty"}
    elif operation == "inspect":
        return claimed(state, config, data["messageId"], data["claim"])
    elif operation == "finish":
        pending = state["pending"][data["messageId"]]
        if pending.get("claim") != data["claim"] or pending["status"] not in {"claimed", "uncertain"}:
            raise ValueError("Claim mismatch")
        outcome = data["outcome"]
        if outcome not in {"delivered", "skipped", "uncertain"}:
            raise ValueError("Invalid outcome")
        if outcome == "uncertain":
            pending["status"] = "uncertain"
        else:
            state["done"].append(data["messageId"])
            del state["pending"][data["messageId"]]
    elif operation == "restart-pages":
        # For expired page tokens, restart the scan at its unchanged base cursor.
        if data["revision"] != state["revision"]:
            return {"status": "conflict"}
        state["pageToken"] = None
    elif operation != "status":
        raise ValueError("Unknown operation")
    return view(state, config)


def run(config_path, operation, data):
    config_path = Path(config_path)
    if not config_path.is_absolute():
        raise ValueError("Connection config must be an absolute path")
    config = json.loads(config_path.read_text())
    state_path = config_path.with_name("state.json")
    with open(config_path.with_name("state.lock"), "a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = json.loads(state_path.read_text()) if state_path.exists() else {
            "schemaVersion": 1, "revision": 0, "mailboxEmail": config["mailboxEmail"],
            "status": "needs_profile", "pending": {}, "done": [],
        }
        if state["schemaVersion"] != 1 or state["mailboxEmail"].lower() != config["mailboxEmail"].lower():
            raise ValueError("Connection config differs from stored mailbox")
        before = json.dumps(state, sort_keys=True)
        result = apply(state, config, operation, data)
        if before != json.dumps(state, sort_keys=True) or not state_path.exists():
            state["revision"] += 1
            temporary = state_path.with_suffix(".tmp")
            with open(temporary, "w") as output:
                os.chmod(temporary, 0o600)
                json.dump(state, output)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, state_path)
            directory = os.open(state_path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        result["revision"] = state["revision"]
        return result


if __name__ == "__main__":
    try:
        operation, config_path = sys.argv[1:3]
        data = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        print(json.dumps(run(config_path, operation, data), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({"error": type(error).__name__, "message": str(error)[:200]}))
        raise SystemExit(1)
