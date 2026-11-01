"""Bounded, read-only archive inspection for the Souz usage-log-analysis skill."""

import argparse
import contextlib
import gzip
import json
import pathlib
import shutil
import stat
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile


MAX_DOWNLOAD = 64 * 1024 * 1024
MAX_FILE = 16 * 1024 * 1024
MAX_SCAN = 128 * 1024 * 1024
MAX_ENTRIES = 10000


def fetch(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("Expected an observed HTTP(S) archive URL")
    if parsed.username or parsed.password:
        raise ValueError("Credentials in URL authority are unsupported")
    folder = pathlib.Path(tempfile.mkdtemp(prefix="souz-usage-logs-"))
    destination = folder / "archive"
    try:
        with urllib.request.urlopen(url, timeout=30) as response, destination.open("wb") as out:
            total = 0
            while True:
                chunk = response.read(65536)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_DOWNLOAD:
                    raise ValueError("Download exceeds 64 MiB")
                out.write(chunk)
        # Composite script stdout is literal; do not append a newline to this path.
        sys.stdout.write(str(destination))
    except Exception:
        shutil.rmtree(folder)
        raise


@contextlib.contextmanager
def members(path):
    """Yield (name, declared size, opener); never extract archive paths."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ENTRIES:
                raise ValueError("Archive exceeds 10000 entries")
            yield [(e.filename, e.file_size, lambda e=e: archive.open(e))
                   for e in entries if not e.is_dir()
                   and not stat.S_ISLNK(e.external_attr >> 16)]
    elif tarfile.is_tarfile(path):
        with tarfile.open(path) as archive:
            entries = []
            for index, entry in enumerate(archive):
                if index >= MAX_ENTRIES:
                    raise ValueError("Archive exceeds 10000 entries")
                if entry.isfile():
                    entries.append((entry.name, entry.size,
                                    lambda e=entry: archive.extractfile(e)))
            yield entries
    else:
        with open(path, "rb") as stream:
            compressed = stream.read(2) == b"\x1f\x8b"
        yield [(pathlib.Path(path).name, None if compressed else pathlib.Path(path).stat().st_size,
                lambda: gzip.open(path, "rb") if compressed else open(path, "rb"))]


def emit(value):
    print(json.dumps(value, ensure_ascii=False))


def inspect_archive(args):
    with members(args.source) as entries:
        selected = entries[args.offset:args.offset + 100]
        emit({"archive": str(pathlib.Path(args.source).resolve()),
              "files": [{"index": args.offset + i, "name": name, "bytes": size}
                        for i, (name, size, _) in enumerate(selected)],
              "total_files": len(entries),
              "next_offset": args.offset + 100 if args.offset + 100 < len(entries) else None})


def scan(args):
    result = {"files_scanned": [], "skipped": [], "excerpts": [], "matched_lines": 0}
    total = 0
    seen_names = set()
    terms = [term.casefold() for term in args.term]
    with members(args.source) as entries:
        chosen = [entry for entry in entries if args.file is None or entry[0] == args.file]
        if args.file is not None and not chosen:
            raise ValueError("File not present in archive")
        for name, size, opener in chosen:
            if name in seen_names:
                result["skipped"].append({"file": name, "reason": "duplicate member name"})
                continue
            seen_names.add(name)
            if (size is not None and size > MAX_FILE) or total >= MAX_SCAN:
                result["skipped"].append({"file": name, "reason": "scan size limit"})
                continue
            with opener() as stream:
                data = stream.read(min(MAX_FILE, MAX_SCAN - total) + 1)
            total += len(data)
            if len(data) > MAX_FILE or total > MAX_SCAN:
                result["skipped"].append({"file": name, "reason": "scan size limit"})
                continue
            try:
                text = data.decode("utf-8-sig")
                if "\x00" in text:
                    raise ValueError("binary")
            except (UnicodeError, ValueError):
                result["skipped"].append({"file": name, "reason": "not UTF-8 text"})
                continue
            lines = text.splitlines()
            result["files_scanned"].append({"file": name, "lines": len(lines)})
            if args.action == "read":
                starts = [(args.start - 1, min(len(lines), args.start - 1 + args.count), None)]
            else:
                starts = []
                for i, line in enumerate(lines):
                    if any(term in line.casefold() for term in terms):
                        match_index = result["matched_lines"]
                        result["matched_lines"] += 1
                        if args.offset <= match_index < args.offset + 20:
                            starts.append((max(0, i - 2), min(len(lines), i + 3), i + 1))
            for start, end, matched_line in starts:
                result["excerpts"].append({
                    "file": name, "matched_line": matched_line,
                    "lines": [{"line": i + 1, "text": lines[i][:1000],
                               "truncated": len(lines[i]) > 1000} for i in range(start, end)]})
    result["coverage_complete"] = not result["skipped"]
    result["next_offset"] = (args.offset + 20 if args.action == "search"
                             and result["matched_lines"] > args.offset + 20 else None)
    emit(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fetch", "inspect", "search", "read"))
    parser.add_argument("source", help="Observed URL for fetch; sandbox archive path otherwise")
    parser.add_argument("--file", help="Exact archive member name from inspect")
    parser.add_argument("--term", action="append", default=[], help="Literal case-insensitive text (OR)")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--start", type=int, default=1)
    parser.add_argument("--count", type=int, default=40)
    args = parser.parse_args()
    if args.offset < 0 or args.start < 1 or not 1 <= args.count <= 100:
        parser.error("offset >= 0, start >= 1, count between 1 and 100 required")
    if args.action == "search" and (not args.term or any(not t for t in args.term)):
        parser.error("search requires nonempty --term")
    if args.action == "read" and args.file is None:
        parser.error("read requires --file")
    try:
        if args.action == "fetch":
            fetch(args.source)
        elif args.action == "inspect":
            inspect_archive(args)
        else:
            scan(args)
    except Exception as error:
        # Network exceptions may contain a signed URL; expose only the error class.
        message = ("Archive download failed; check reachability, URL validity and size (64 MiB limit)"
                   if args.action == "fetch" else str(error))
        print(json.dumps({"error": type(error).__name__, "message": message}), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
