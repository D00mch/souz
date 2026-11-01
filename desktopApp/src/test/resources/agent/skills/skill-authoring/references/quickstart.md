# Creation, invocation, and editing

This example checks the filesystem lifecycle. For an integration, first verify the
real user scenario as described in SKILL.md; this fixture is not a substitute.

Example request: “Create a personal skill that normalizes whitespace in text.”
Check that `my-text-normalizer` is available as a new ID before running this
example. These are `RunSkillCommand` payloads, not shell commands.

Create and locally check a draft, then install it as an ordinary folder:

```json
{
  "skillId": "skill-authoring",
  "arguments": {
    "runtime": "PYTHON",
    "script": "from pathlib import Path\nimport os, subprocess, sys, tempfile\nbase = Path('/souz/state/skills')\nassert Path('/.dockerenv').exists() and base.is_dir(), 'Docker sandbox required'\ntarget = base / 'my-text-normalizer'\nassert not target.exists(), 'Skill already exists; inspect it before editing'\nwork = Path('/souz/workspace/skill-authoring')\nwork.mkdir(parents=True, exist_ok=True)\ndraft = Path(tempfile.mkdtemp(prefix='draft-', dir=work))\n(draft / 'scripts').mkdir()\nsource = 'import sys\\nprint(\" \".join(sys.stdin.read().split()))\\n'\ncompile(source, 'normalize.py', 'exec')\n(draft / 'scripts/normalize.py').write_text(source, encoding='utf-8')\n(draft / 'SKILL.md').write_text('---\\nname: my-text-normalizer\\ndescription: Normalize whitespace when the user asks to clean up spacing.\\n---\\nUse RunSkillCommand with skillId my-text-normalizer, scriptPath scripts/normalize.py, runtime PYTHON and the input text in stdin. Return stdout.\\n', encoding='utf-8')\nresult = subprocess.run([sys.executable, str(draft / 'scripts/normalize.py')], input='  Hello   Souz  ', text=True, capture_output=True, check=True)\nassert result.stdout == 'Hello Souz\\n'\nassert not target.exists(), 'Destination appeared during drafting'\nos.rename(draft, target)\nprint('Created my-text-normalizer; load it with GetSkillByName before use')"
  }
}
```

Call `GetSkillByName` with `{"skillId":"my-text-normalizer"}`. Check that it
returns the instructions and execution schema, then invoke:

```json
{
  "skillId": "my-text-normalizer",
  "arguments": {
    "runtime": "PYTHON",
    "scriptPath": "scripts/normalize.py",
    "stdin": "  Hello   Souz  "
  }
}
```

Expected stdout is `Hello Souz` followed by a newline. Also check `exitCode == 0`
and `timedOut == false`.

Follow-up request: “Make that skill lowercase the text too.” Inspect the existing
files first. This example replaces the script and description one file at a time; it is not
a multi-file update transaction:

```json
{
  "skillId": "skill-authoring",
  "arguments": {
    "runtime": "PYTHON",
    "script": "from pathlib import Path\nimport os, tempfile\nroot = Path('/souz/state/skills/my-text-normalizer')\nassert Path('/.dockerenv').exists(), 'Docker sandbox required'\nassert not root.is_symlink() and root.is_dir()\nassert not (root / 'stored-skill.json').exists() and not (root / 'bundles').exists(), 'Registry-managed skill needs operator migration'\nassert not (root / 'scripts').is_symlink()\ntarget = root / 'scripts/normalize.py'\nassert not target.is_symlink() and target.is_file()\nold = target.read_text(encoding='utf-8')\nassert old.count('sys.stdin.read().split()') == 1, 'Inspect changed source before editing'\nnew = old.replace('sys.stdin.read().split()', 'sys.stdin.read().lower().split()')\ncompile(new, str(target), 'exec')\nfd, temp = tempfile.mkstemp(prefix='.edit-', suffix='.py', dir=target.parent)\ntry:\n    with os.fdopen(fd, 'w', encoding='utf-8') as output:\n        output.write(new)\n    os.replace(temp, target)\nfinally:\n    if os.path.exists(temp):\n        os.unlink(temp)\nprint('Updated my-text-normalizer; reload and repeat the example')\nmanifest = root / 'SKILL.md'\nassert not manifest.is_symlink()\ntext = manifest.read_text(encoding='utf-8')\ntext = text.replace('Normalize whitespace when', 'Lowercase text and normalize whitespace when')\nfd, temp = tempfile.mkstemp(prefix='.edit-', suffix='.md', dir=root)\ntry:\n    with os.fdopen(fd, 'w', encoding='utf-8') as output:\n        output.write(text)\n    os.replace(temp, manifest)\nfinally:\n    if os.path.exists(temp):\n        os.unlink(temp)"
  }
}
```

Call `GetSkillByName` again and repeat the invocation above: expected stdout is
`hello souz` followed by a newline. No backend or container restart is needed.
