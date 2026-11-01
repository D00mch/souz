"""Public YouTube and Rutube search. YouTube credentials live outside the bundle."""
import argparse
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ACCESS_FILE = Path(__file__).resolve().parents[2] / ".youtube-auth"
API = "https://www.googleapis.com/youtube/v3/"


def api_call(resource, params, key):
    url = API + resource + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={
        "X-Goog-Api-Key": key,
        "User-Agent": "souz-youtube-search/1.0",
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.load(response)
        if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
            raise ValueError("invalid response")
        return payload
    except urllib.error.HTTPError as error:
        reason = "requestFailed"
        try:
            value = json.load(error)["error"]["errors"][0]["reason"]
            if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_]{1,80}", value):
                reason = value
        except (ValueError, KeyError, IndexError, TypeError):
            pass
        raise RuntimeError(f"YouTube HTTP {error.code}: {reason}") from None
    except (urllib.error.URLError, OSError):
        raise RuntimeError("YouTube недоступен или истекло время ожидания") from None
    except (ValueError, TypeError):
        raise RuntimeError("YouTube вернул некорректный ответ") from None


def search(query, key):
    videos, seen, tokens = [], set(), set()
    token = None
    # Bound quota consumption and total time for sparse/unstable search results.
    for _ in range(3):
        params = {"part": "snippet", "q": query, "type": "video",
                  "order": "relevance", "maxResults": 5}
        if token:
            params["pageToken"] = token
        page = api_call("search", params, key)
        ids = []
        for item in page["items"]:
            video_id = item.get("id", {}).get("videoId")
            if video_id and video_id not in seen:
                seen.add(video_id)
                ids.append(video_id)
        if ids:
            details = api_call("videos", {"part": "snippet", "id": ",".join(ids)}, key)
            by_id = {item["id"]: item["snippet"] for item in details["items"]}
            for video_id in ids:
                snippet = by_id.get(video_id)
                if snippet is None:
                    continue
                videos.append({"links": [{"type": "WEB", "url": "https://www.youtube.com/watch?v=" + video_id},
                                         {"type": "PLAY", "url": "startube://www.youtube.com/watch?v=" + video_id,
                                          "packageName": "ru.sberdevices.startube"}],
                               "title": snippet["title"],
                               "description": snippet.get("description", ""),
                               "publishedAt": snippet["publishedAt"]})
                if len(videos) == 5:
                    return {"query": query, "videos": videos, "complete": True, "note": None}
        token = page.get("nextPageToken")
        if not token or token in tokens:
            break
        tokens.add(token)
    note = ("Найдено меньше пяти доступных видео; достигнут предел в три страницы поиска."
            if token else "YouTube вернул меньше пяти доступных видео по этому запросу.")
    return {"query": query, "videos": videos, "complete": False, "note": note}


def main():
    parser = argparse.ArgumentParser(description="Find five videos by relevance")
    parser.add_argument("--platform", choices=("youtube", "rutube"), default="youtube")
    parser.add_argument("query")
    args = parser.parse_args()
    query = args.query.strip()
    if not query:
        parser.error("поисковый запрос не должен быть пустым")
    try:
        if args.platform == "rutube":
            from rutube import search as search_rutube
            result = search_rutube(query)
        else:
            try:
                key = json.loads(ACCESS_FILE.read_text(encoding="utf-8"))["key"]
                if not isinstance(key, str) or not key.strip():
                    raise ValueError("empty key")
            except (OSError, ValueError, KeyError, TypeError):
                raise RuntimeError("Ключ YouTube не настроен или файл доступа повреждён") from None
            result = search(query, key.strip())
    except RuntimeError as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    except (KeyError, TypeError, AttributeError, ValueError):
        print(json.dumps({"error": f"{args.platform} вернул некорректные метаданные"}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
