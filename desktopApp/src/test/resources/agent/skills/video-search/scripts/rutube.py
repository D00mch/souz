"""Unauthenticated public Rutube search, preserving native relevance order."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request


def fetch_page(query, page):
    params = urllib.parse.urlencode({"query": query, "page": page, "limit": 5, "sort": "rank"})
    request = urllib.request.Request("https://rutube.ru/api/search/video/?" + params,
                                     headers={"Accept": "application/json",
                                              "User-Agent": "souz-video-search/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            result = json.load(response)
        if (not isinstance(result, dict) or not isinstance(result.get("results"), list)
                or not isinstance(result.get("has_next"), bool)):
            raise ValueError("invalid response")
        return result
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Rutube HTTP {error.code}") from None
    except (urllib.error.URLError, OSError):
        raise RuntimeError("Rutube недоступен или истекло время ожидания") from None
    except (ValueError, TypeError):
        raise RuntimeError("Rutube вернул некорректный ответ") from None


def search(query):
    videos, seen = [], set()
    for page in range(1, 4):
        result = fetch_page(query, page)
        for item in result["results"]:
            if not isinstance(item, dict):
                raise RuntimeError("Rutube вернул некорректные метаданные")
            if any(item.get(flag) for flag in ("is_deleted", "is_hidden", "is_locked")):
                continue
            video_id = item.get("id")
            if not isinstance(video_id, str) or not re.fullmatch(r"[a-fA-F0-9]{32}", video_id):
                raise RuntimeError("Rutube вернул некорректный идентификатор видео")
            if video_id in seen:
                continue
            title = item.get("title")
            description = item.get("description")
            published = item.get("publication_ts")
            if (not isinstance(title, str) or not title.strip()
                    or (description is not None and not isinstance(description, str))
                    or (published is not None and not isinstance(published, str))):
                raise RuntimeError("Rutube вернул некорректные метаданные")
            seen.add(video_id)
            videos.append({"links": [{"type": "WEB", "url": f"https://rutube.ru/video/{video_id}/"},
                                     {"type": "PLAY", "url": f"rutube://rutube.ru/video/{video_id}/",
                                      "packageName": "ru.rutube.app"}],
                           "title": title, "description": description or "",
                           "publishedAt": published or None})
            if len(videos) == 5:
                return {"query": query, "videos": videos, "complete": True, "note": None}
        if not result["has_next"]:
            break
    note = ("Найдено меньше пяти доступных видео; достигнут предел в три страницы поиска."
            if result["has_next"] else "Rutube вернул меньше пяти доступных видео по этому запросу.")
    return {"query": query, "videos": videos, "complete": False, "note": note}
