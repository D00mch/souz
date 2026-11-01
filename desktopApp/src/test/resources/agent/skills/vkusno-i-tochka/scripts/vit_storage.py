"""User-container state, durable operation journal, and inter-process exclusion."""
import contextlib
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sqlite3
import time

from vit_common import Failure


def state_directory():
    # Loose and registry-managed bundles share the same sibling state directory.
    root = Path(__file__).resolve().parent.parent
    if root.parent.name == "bundles":
        root = root.parent.parent
    if root.name != "vkusno-i-tochka":
        raise Failure("STORAGE_UNAVAILABLE", "Пакет должен быть установлен под ID vkusno-i-tochka.")
    return root.parent / ".vkusno-i-tochka-state"


class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.db = None

    @contextlib.contextmanager
    def locked(self):
        os.umask(0o077)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.directory, 0o700)
        with (self.directory / "lock").open("a") as lock:
            deadline = time.monotonic() + 5
            while True:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise Failure("BUSY", "Другой чат выполняет операцию «Вкусно — и точка».",
                                      retryable=True, retry_after=5)
                    time.sleep(0.05)
            try:
                self.db = sqlite3.connect(self.directory / "state.sqlite3", timeout=5)
                self.db.execute("PRAGMA secure_delete=ON")
                self.db.execute("PRAGMA synchronous=FULL")
                version = self.db.execute("PRAGMA user_version").fetchone()[0]
                if version not in (0, 1):
                    raise Failure("STORAGE_UNAVAILABLE", "Неподдерживаемая версия хранилища.")
                self.db.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                self.db.execute("CREATE TABLE IF NOT EXISTS operations "
                                "(id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                self.db.execute("PRAGMA user_version=1")
                self.db.commit()
                if self.get("hmac_key") is None:
                    self.put("hmac_key", secrets.token_hex(32))
                yield self
            finally:
                if self.db is not None:
                    self.db.close()
                    self.db = None
                fcntl.flock(lock, fcntl.LOCK_UN)

    def get(self, key, default=None):
        row = self.db.execute("SELECT value FROM state WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def put(self, key, value):
        self.update({key: value})

    def update(self, entries):
        with self.db:
            for key, value in entries.items():
                if value is None:
                    self.db.execute("DELETE FROM state WHERE key=?", (key,))
                else:
                    self.db.execute("INSERT OR REPLACE INTO state VALUES (?, ?)",
                                    (key, json.dumps(value, ensure_ascii=False, allow_nan=False)))

    def fingerprint(self, value):
        encoded = json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()
        return hmac.new(bytes.fromhex(self.get("hmac_key")), encoded, hashlib.sha256).hexdigest()

    def operation(self, request_id):
        row = self.db.execute("SELECT value FROM operations WHERE id=?", (request_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def save_operation(self, request_id, operation):
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO operations VALUES (?, ?)",
                            (request_id, json.dumps(operation, ensure_ascii=False, allow_nan=False)))

    def expire_results(self, now):
        # Keep small tombstones: an expired request ID must never become a new mutation.
        for request_id, raw in self.db.execute("SELECT id, value FROM operations").fetchall():
            operation = json.loads(raw)
            if operation["status"] == "done" and now - operation["created"] > 86400:
                operation.pop("result", None)
                operation.pop("before", None)
                operation["status"] = "expired"
                self.save_operation(request_id, operation)
