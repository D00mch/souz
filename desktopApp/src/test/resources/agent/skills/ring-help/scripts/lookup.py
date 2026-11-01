"""Looks up the official Sber smart-ring help base. Called by help.sh, not by the model directly.

Access data lives outside the skill bundle (see ACCESS_FILE).
No shebang on purpose: a bare `/usr/bin/...` line trips the bundle validator.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# Resolved relative to this file, not as an absolute host path: skills execute inside a
# container (docker exec --workdir /souz/state/skills/<skill>), where the host's
# /srv/souz-skills/... does not exist. ring-health's wrapper resolves its own access
# file the same way, which is why it kept working after the sandbox moved to Docker.
ACCESS_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, ".help-access"
)
ENDPOINT = "https://gigachat-search-proxy.prom.fine.sberdevices.ru/v2/chat/completions"

# Formatting spec for the search backend: facts only, no prose furniture, so the
# model gets material it can rework rather than a finished-sounding reply.
TEMPLATE = (
    "Составь фактическую выжимку по вопросу об умном кольце Сбера. Используй только данные из контекста. Если сведений нет, скажи об этом. "
    "Без вступлений и выводов, без ссылок, без разметки и списков — сплошной текст, "
    "шаги через точку с запятой, до 700 символов.\n\n"
    "#КОНТЕКСТ\n----------------------\n{context}\n"
)


def fail(text):
    print("Error: " + text, file=sys.stderr)
    raise SystemExit(1)


def read_access():
    try:
        with open(ACCESS_FILE, encoding="utf-8") as handle:
            value = json.load(handle).get("key")
    except FileNotFoundError:
        fail("help access file is missing")
    except json.JSONDecodeError:
        fail("help access file is malformed")
    if not value:
        fail("help access file is incomplete")
    return value


def lookup(question):
    payload = {
        "prompt": question,
        "chatConfig": {"model": "GigaChat-2-Pro", "temperature": 0.3},
        "apiKey": read_access(),
        "productIds": ["ring"],
        "qdrantConfig": {
            "collectionName": "sberdevices-help",
            "action": {"type": "search"},
            "similarResults": 10,
        },
        "phoenixConfig": {"metadata": {"service-name": "sberdevices-help"}},
        "cacheConfig": {"enabled": False, "threshold": 0.95, "strictSources": True},
        "systemPromptTemplate": TEMPLATE,
    }
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        fail("help service returned %s" % error.code)
    except urllib.error.URLError as error:
        fail("help service unreachable: %s" % error.reason)
    except json.JSONDecodeError:
        fail("help service returned a malformed response")


def main():
    if len(sys.argv) < 2 or not sys.argv[1].strip():
        fail("question is required")

    result = lookup(sys.argv[1].strip())
    text = " ".join((result.get("message") or "").split())
    sources = result.get("metadata") or []

    if not text:
        print("NO_ANSWER")
        return

    # searchScore tells the model whether the help base actually covers the question:
    # the service answers fluently even when it does not (an off-topic question scored
    # ~0.6 against ~0.78 for a real one).
    best = max((item.get("searchScore") or 0.0) for item in sources) if sources else 0.0
    print("RELEVANCE=%.2f" % best)
    print("MATERIAL: " + text)
    for item in sources[:1]:
        heading = ((item.get("heading") or {}).get("text") or "").strip()
        link = ((item.get("source") or {}).get("url") or "").strip()
        if heading:
            print("SOURCE\t%s\t%s" % (heading, link))


if __name__ == "__main__":
    main()
