---
name: story_interactive_python
description: Interactive family story engine for TV demos. Use when the user wants a short choose-your-own-adventure story, a bedtime story, or an interactive tale controlled by voice choices. Runs offline through scripts/story_engine.py with the PYTHON runtime.
author: Souz demo
version: 1.0.0
source: https://clawhub.ai/api/v1/packages/story
---

# Interactive Story Skill

This skill adapts the ClawHub `Story` idea for Android TV demos. It generates
a short interactive scene, offers three voice-friendly choices, and returns a
compact state object that can be passed back on the next turn.

If the recent assistant message offered story choices A, B, C and the user
replies with a short choice such as "A", "B", "C", "А", "Б", "В", "I choose B",
or "вариант Б", treat it as a request to continue this skill.

Use this skill when the user asks for:

- an interactive story
- a bedtime or family story
- a choose-your-own-adventure game
- a story where they can pick what happens next

## Runtime

Run `scripts/story_engine.py` through `RunSkillCommand` with `runtime=PYTHON`.
The script uses only the Python standard library and does not need network,
files, pip packages, or a virtual environment.

## Input

Prefer passing a JSON object via stdin:

```json
{
  "action": "start",
  "theme": "space adventure with a friendly robot",
  "hero": "Mira",
  "audience": "family"
}
```

For the next turn, pass the previous `state` object plus the user's choice:

```json
{
  "action": "continue",
  "choice": "B",
  "state": {
    "hero": "Mira",
    "theme": "space adventure with a friendly robot",
    "chapter": 1,
    "mood": "curious",
    "score": 0
  }
}
```

The script also accepts `"action": "choose"` as an alias for `"continue"` when
the model describes a voice choice turn.

## Output

The script returns Markdown with:

- scene title
- short story text
- three clear choices: A, B, C
- compact `STATE_JSON` for the next turn

## Demo Prompts

- "Start an interactive story about a brave robot on Mars."
- "I choose B."
- "Make a bedtime story where my daughter chooses what happens next."
- "Continue the story and make it funny."

## Tool Call Examples

Start:

```json
{
  "skillId": "story-interactive-python",
  "runtime": "PYTHON",
  "scriptPath": "scripts/story_engine.py",
  "stdin": "{\"action\":\"start\",\"theme\":\"brave robot on Mars\",\"hero\":\"Mira\",\"audience\":\"family\"}"
}
```

Continue:

```json
{
  "skillId": "story-interactive-python",
  "runtime": "PYTHON",
  "scriptPath": "scripts/story_engine.py",
  "stdin": "{\"action\":\"continue\",\"choice\":\"B\",\"state\":{\"hero\":\"Mira\",\"theme\":\"brave robot on Mars\",\"chapter\":1,\"mood\":\"curious\",\"score\":0}}"
}
```
