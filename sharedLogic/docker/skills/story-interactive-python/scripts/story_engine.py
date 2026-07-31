"""Offline interactive story helper for the Souz Android Python skill demo."""

from __future__ import annotations

import argparse
import json
import random
import sys
from dataclasses import dataclass, asdict


MAX_CHAPTER = 5


@dataclass
class StoryState:
    hero: str = "Mira"
    theme: str = "a cozy space adventure"
    audience: str = "family"
    chapter: int = 0
    mood: str = "curious"
    score: int = 0


CHOICE_EFFECTS = {
    "A": ("brave", 2),
    "B": ("clever", 1),
    "C": ("kind", 1),
}


SCENE_IMAGES = {
    "brave": "a bright doorway opening in a silent hall",
    "clever": "tiny lights blinking like a secret code",
    "kind": "a small companion waiting for help",
    "curious": "a map unfolding with one glowing path",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate an interactive story scene.")
    parser.add_argument("--action", choices=["start", "continue", "choose"], default=None)
    parser.add_argument("--theme", default=None)
    parser.add_argument("--hero", default=None)
    parser.add_argument("--audience", default=None)
    parser.add_argument("--choice", default=None)
    args = parser.parse_args()

    request = read_request()
    action = args.action or request.get("action") or "start"
    if action in {"continue", "choose"}:
        state = state_from_request(request)
        choice = normalize_choice(args.choice or request.get("choice"))
        state = apply_choice(state, choice)
    else:
        state = StoryState(
            hero=args.hero or request.get("hero") or "Mira",
            theme=args.theme or request.get("theme") or "a cozy space adventure",
            audience=args.audience or request.get("audience") or "family",
        )
        choice = None

    print(render_scene(state, choice))
    return 0


def read_request() -> dict:
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as error:
        return {"theme": raw, "parse_warning": str(error)}
    return data if isinstance(data, dict) else {}


def state_from_request(request: dict) -> StoryState:
    raw_state = request.get("state")
    if not isinstance(raw_state, dict):
        raw_state = {}
    return StoryState(
        hero=str(raw_state.get("hero") or request.get("hero") or "Mira"),
        theme=str(raw_state.get("theme") or request.get("theme") or "a cozy space adventure"),
        audience=str(raw_state.get("audience") or request.get("audience") or "family"),
        chapter=int(raw_state.get("chapter") or 0),
        mood=str(raw_state.get("mood") or "curious"),
        score=int(raw_state.get("score") or 0),
    )


def normalize_choice(raw_choice: object) -> str:
    choice = str(raw_choice or "A").strip().upper()
    choice = choice.replace("А", "A").replace("В", "B").replace("С", "C")
    if choice in {"1", "FIRST", "ПЕРВЫЙ", "ПЕРВАЯ"}:
        return "A"
    if choice in {"2", "SECOND", "ВТОРОЙ", "ВТОРАЯ", "Б"}:
        return "B"
    if choice in {"3", "THIRD", "ТРЕТИЙ", "ТРЕТЬЯ"}:
        return "C"
    return choice if choice in CHOICE_EFFECTS else "A"


def apply_choice(state: StoryState, choice: str) -> StoryState:
    mood, points = CHOICE_EFFECTS[choice]
    return StoryState(
        hero=state.hero,
        theme=state.theme,
        audience=state.audience,
        chapter=min(state.chapter + 1, MAX_CHAPTER),
        mood=mood,
        score=state.score + points,
    )


def render_scene(state: StoryState, choice: str | None) -> str:
    if state.chapter >= MAX_CHAPTER:
        return render_finale(state)

    rng = random.Random(f"{state.hero}:{state.theme}:{state.chapter}:{state.mood}:{state.score}")
    image = SCENE_IMAGES.get(state.mood, SCENE_IMAGES["curious"])
    scene = rng.choice(
        [
            f"{state.hero} stepped closer and noticed {image}.",
            f"In the middle of {state.theme}, {state.hero} found {image}.",
            f"The room grew quiet. Then {state.hero} saw {image}.",
        ]
    )
    consequence = ""
    if choice:
        consequence = f"\n\nYour choice {choice} made the story feel more {state.mood}."

    next_state = asdict(state)
    return (
        f"## Chapter {state.chapter + 1}: {title_for(state)}\n\n"
        f"{scene} The next decision had to be simple enough to say out loud, "
        f"but important enough to change the ending.{consequence}\n\n"
        "Choose what happens next:\n\n"
        "A. Open the strange door.\n"
        "B. Solve the glowing clue.\n"
        "C. Help the quiet companion.\n\n"
        f"`STATE_JSON: {json.dumps(next_state, ensure_ascii=False, separators=(',', ':'))}`"
    )


def render_finale(state: StoryState) -> str:
    if state.score >= 7:
        ending = "The adventure ended with a bold rescue and a room full of applause."
    elif state.score >= 5:
        ending = "The adventure ended with a clever escape and a promise to return."
    else:
        ending = "The adventure ended softly, with a new friend and a warm light in the window."
    return (
        f"## Finale: {state.hero}'s Choice\n\n"
        f"{ending}\n\n"
        f"Final score: {state.score}\n\n"
        "`STATE_JSON: null`"
    )


def title_for(state: StoryState) -> str:
    words = [word.capitalize() for word in state.theme.replace("-", " ").split()[:4]]
    return " ".join(words) if words else "The Next Door"


if __name__ == "__main__":
    raise SystemExit(main())
