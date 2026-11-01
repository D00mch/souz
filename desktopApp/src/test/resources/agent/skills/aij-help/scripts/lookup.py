"""Looks up the AI Journey conference knowledge base. Called by help.sh, not by the model directly.

Sibling of device-help/scripts/lookup.py: same shared collection, same access key
(sberdevices-help holds help / help-b2b / ai-journey side by side, separated only by
metadata.helpService). Access data lives outside the skill bundle (see ACCESS_FILE).
No shebang on purpose: a bare `/usr/bin/...` line trips the bundle validator.
"""
import json
import os
import re
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
    "Составь фактическую выжимку из контекста по вопросу о конференции AI Journey: "
    "даты, формат, участие, программа, треки — смотря о чём вопрос. "
    "Без вступлений и выводов, без ссылок, без разметки и списков — сплошной текст, "
    "пункты через точку с запятой, до 700 символов.\n\n"
    "#КОНТЕКСТ\n----------------------\n{context}\n"
)


def fail(text):
    print("Error: " + text, file=sys.stderr)
    raise SystemExit(1)


def flatten(raw):
    """Strips markdown the service sometimes emits despite being asked not to.

    Bullets and bold markers are noise once the model reworks the text into speech,
    and they cost context on every turn, so they are removed here rather than left
    for the model to ignore.
    """
    text = re.sub(r"^\s*[-*+]\s+", "; ", raw, flags=re.MULTILINE)
    text = re.sub(r"^\s*#{1,6}\s*", "", text, flags=re.MULTILINE)
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = text.replace("**", "").replace("`", "")
    text = " ".join(text.split())
    return re.sub(r"(;\s*){2,}", "; ", text).strip("; ").strip()


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
        "qdrantConfig": {
            "filter": {"must": [{"key": "metadata.helpService", "match": {"any": ["ai-journey"]}}]},
            "similarResults": 10,
        },
        "phoenixConfig": {"metadata": {"service-name": "AIJ"}},
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
    text = flatten(result.get("message") or "")
    sources = result.get("metadata") or []

    if not text:
        print("NO_ANSWER")
        return

    # searchScore tells the model whether the knowledge base actually covers the question:
    # the service answers fluently even when it does not. Measured on this section:
    # real questions land at 0.63-0.86 (tickets 0.63, broadcast 0.65), off-topic ones at
    # 0.54-0.57 — hence the skill's 0.6 cut-off rather than device-help's 0.7.
    best = max((item.get("searchScore") or 0.0) for item in sources) if sources else 0.0
    print("RELEVANCE=%.2f" % best)
    print("MATERIAL: " + text)
    # Heading only: every article in this section carries the same aij.ru link, so a
    # URL line would just eat context without telling the model anything.
    for item in sources[:1]:
        heading = ((item.get("heading") or {}).get("text") or "").strip()
        if heading:
            print("SECTION\t" + heading)


if __name__ == "__main__":
    main()
