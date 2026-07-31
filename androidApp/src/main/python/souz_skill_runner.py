import contextlib
import io
import json
import os
import runpy
import sys
import time
import traceback


_OUTPUT_LIMIT_BYTES = 64 * 1024
_TRUNCATION_PREFIX = "\n...[truncated "


class _SkillTimeout(Exception):
    pass


class _BoundedTextIO(io.TextIOBase):
    def __init__(self, limit_bytes=_OUTPUT_LIMIT_BYTES):
        super().__init__()
        self._limit_bytes = int(limit_bytes)
        self._buffer = io.BytesIO()
        self._stored_bytes = 0
        self._truncated_bytes = 0

    def writable(self):
        return True

    def write(self, value):
        text = str(value)
        encoded = text.encode("utf-8", errors="replace")
        remaining = max(0, self._limit_bytes - self._stored_bytes)
        stored = min(remaining, len(encoded))
        if stored:
            self._buffer.write(encoded[:stored])
            self._stored_bytes += stored
        self._truncated_bytes += len(encoded) - stored
        return len(text)

    def flush(self):
        pass

    def getvalue(self):
        output = self._buffer.getvalue().decode("utf-8", errors="replace")
        if self._truncated_bytes <= 0:
            return output
        return f"{output}{_TRUNCATION_PREFIX}{self._truncated_bytes} bytes]"


def run_skill_command(
    script,
    script_path,
    args,
    working_directory,
    environment_keys,
    environment_values,
    stdin,
    timeout_millis,
):
    args = [str(value) for value in args] if args is not None else []
    env = {
        str(key): str(value)
        for key, value in zip(
            list(environment_keys) if environment_keys is not None else [],
            list(environment_values) if environment_values is not None else [],
        )
    }
    timeout_millis = int(timeout_millis or 0)
    deadline = (
        time.monotonic() + (timeout_millis / 1000.0)
        if timeout_millis > 0
        else None
    )

    import_paths = _skill_import_paths(
        script_path=script_path,
        working_directory=working_directory,
        skill_root=env.get("SOUZ_SKILL_ROOT"),
    )
    stdout = _BoundedTextIO()
    stderr = _BoundedTextIO()
    exit_code = 0
    timed_out = False

    old_argv = sys.argv[:]
    old_path = sys.path[:]
    old_stdin = sys.stdin
    old_cwd = os.getcwd()
    old_env = os.environ.copy()
    old_trace = sys.gettrace()

    def trace_timeout(frame, event, arg):
        if deadline is not None and time.monotonic() > deadline:
            raise _SkillTimeout(f"Python skill timed out after {timeout_millis} ms.")
        return trace_timeout

    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        try:
            os.environ.update(env)
            if working_directory:
                os.chdir(str(working_directory))

            _cleanup_skill_modules(import_paths)
            _prepend_import_paths(import_paths)
            sys.argv = [str(script_path) if script_path else "python"] + args
            sys.stdin = io.StringIO(stdin or "")

            if deadline is not None:
                sys.settrace(trace_timeout)

            if script_path:
                runpy.run_path(str(script_path), run_name="__main__")
            else:
                globals_dict = {
                    "__name__": "__main__",
                    "__file__": "<souz-skill-inline>",
                    "__package__": None,
                }
                exec(compile(str(script), "<souz-skill-inline>", "exec"), globals_dict)
        except _SkillTimeout as error:
            timed_out = True
            exit_code = -1
            print(str(error), file=sys.stderr)
        except SystemExit as error:
            exit_code = _system_exit_code(error)
        except BaseException:
            exit_code = 1
            traceback.print_exc(file=sys.stderr)
        finally:
            sys.settrace(old_trace)
            sys.stdin = old_stdin
            sys.argv = old_argv
            sys.path[:] = old_path
            os.chdir(old_cwd)
            os.environ.clear()
            os.environ.update(old_env)
            _cleanup_skill_modules(import_paths)

    return json.dumps(
        {
            "exitCode": exit_code,
            "timedOut": timed_out,
            "stdout": stdout.getvalue(),
            "stderr": stderr.getvalue(),
        }
    )


def _skill_import_paths(script_path, working_directory, skill_root):
    paths = []
    if script_path:
        paths.append(os.path.dirname(str(script_path)))
    if working_directory:
        paths.append(str(working_directory))
    if skill_root:
        paths.append(str(skill_root))
    return paths


def _prepend_import_paths(paths):
    for path in reversed(paths):
        if path and path not in sys.path:
            sys.path.insert(0, path)


def _cleanup_skill_modules(paths):
    roots = [_safe_realpath(path) for path in paths if path]
    roots = [root for root in roots if root]
    if not roots:
        return

    for module_name, module in list(sys.modules.items()):
        module_file = getattr(module, "__file__", None)
        if module_file and _path_under_any(module_file, roots):
            sys.modules.pop(module_name, None)


def _path_under_any(path, roots):
    candidate = _safe_realpath(path)
    if not candidate:
        return False
    for root in roots:
        try:
            if os.path.commonpath([candidate, root]) == root:
                return True
        except ValueError:
            continue
    return False


def _safe_realpath(path):
    try:
        return os.path.realpath(str(path))
    except (TypeError, ValueError, OSError):
        return None


def _system_exit_code(error):
    code = error.code
    if code is None:
        return 0
    if isinstance(code, int):
        return code
    print(str(code), file=sys.stderr)
    return 1
