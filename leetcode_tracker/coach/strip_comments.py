"""从源码中剥离注释，供陪练上下文喂模（不写回库）。"""

from __future__ import annotations

import io
import tokenize


def strip_code_comments(code: str, language: str = "") -> str:
    """按语言去掉注释；保留字符串字面量。结果压缩多余空行。"""
    if not code:
        return ""
    lang = _normalize_lang(language)
    if lang == "python":
        stripped = _strip_python(code)
    elif lang == "ruby":
        stripped = _strip_ruby(code)
    elif lang == "sql":
        stripped = _strip_sql(code)
    elif lang in {"bash", "sh", "shell", "r"}:
        stripped = _strip_hash_outside_strings(code)
    elif lang == "php":
        stripped = _strip_c_like(code, also_hash=True)
    else:
        # java / cpp / c / js / ts / go / rust / csharp / kotlin / swift / scala …
        stripped = _strip_c_like(code, also_hash=False)
    return _collapse_blank_lines(stripped)


def _normalize_lang(language: str) -> str:
    lang = (language or "").strip().lower()
    aliases = {
        "c++": "cpp",
        "c#": "csharp",
        "cs": "csharp",
        "js": "javascript",
        "ts": "typescript",
        "python3": "python",
        "py": "python",
        "golang": "go",
        "rb": "ruby",
        "mysql": "sql",
        "postgresql": "sql",
        "postgres": "sql",
        "tsql": "sql",
        "plsql": "sql",
    }
    return aliases.get(lang, lang)


def _collapse_blank_lines(code: str) -> str:
    out: list[str] = []
    blank = False
    for line in code.splitlines():
        if line.strip():
            out.append(line.rstrip())
            blank = False
        elif out and not blank:
            out.append("")
            blank = True
    return "\n".join(out).strip()


def _strip_python(code: str) -> str:
    """去掉 # 注释与独立 docstring，保留普通字符串字面量。"""
    try:
        tokens = list(tokenize.generate_tokens(io.StringIO(code).readline))
    except (tokenize.TokenError, IndentationError):
        return _strip_hash_outside_strings(code)

    ranges: list[tuple[int, int, int, int]] = []
    prev_significant = tokenize.ENCODING
    for tok in tokens:
        if tok.type == tokenize.COMMENT:
            ranges.append((*tok.start, *tok.end))
            continue
        if tok.type == tokenize.ENDMARKER:
            break
        if tok.type == tokenize.STRING and prev_significant in {
            tokenize.INDENT,
            tokenize.NEWLINE,
            tokenize.NL,
            tokenize.ENCODING,
        }:
            ranges.append((*tok.start, *tok.end))
            prev_significant = tokenize.NEWLINE
            continue
        if tok.type not in {tokenize.NL, tokenize.COMMENT, tokenize.ENCODING}:
            prev_significant = tok.type

    return _blank_ranges(code, ranges)


def _blank_ranges(code: str, ranges: list[tuple[int, int, int, int]]) -> str:
    """按 1-based (line, col) 区间清空，保留换行符。"""
    lines = code.splitlines(keepends=True)
    if not lines or not ranges:
        return code
    chars = [list(line) for line in lines]
    for start_l, start_c, end_l, end_c in ranges:
        for li in range(start_l, end_l + 1):
            if li < 1 or li > len(chars):
                continue
            row = chars[li - 1]
            content_len = len(row)
            while content_len > 0 and row[content_len - 1] in "\r\n":
                content_len -= 1
            sc = start_c if li == start_l else 0
            ec = end_c if li == end_l else content_len
            sc = max(0, min(sc, content_len))
            ec = max(sc, min(ec, content_len))
            for i in range(sc, ec):
                row[i] = " "
    return "".join("".join(row) for row in chars)


def _strip_hash_outside_strings(code: str) -> str:
    return _scan(
        code,
        line_markers=("#",),
        block_start=None,
        block_end=None,
        allow_triple=True,
        backtick=False,
    )


def _strip_sql(code: str) -> str:
    return _scan(
        code,
        line_markers=("--",),
        block_start="/*",
        block_end="*/",
        allow_triple=False,
        backtick=False,
        string_quotes=("'",),
    )


def _strip_c_like(code: str, *, also_hash: bool) -> str:
    markers: tuple[str, ...] = ("//", "#") if also_hash else ("//",)
    return _scan(
        code,
        line_markers=markers,
        block_start="/*",
        block_end="*/",
        allow_triple=False,
        backtick=True,
        string_quotes=('"', "'"),
    )


def _strip_ruby(code: str) -> str:
    out: list[str] = []
    i = 0
    n = len(code)
    in_sq = False
    in_dq = False
    in_block = False
    while i < n:
        ch = code[i]
        if in_block:
            if code.startswith("=end", i) and _line_start(code, i):
                while i < n and code[i] != "\n":
                    i += 1
                in_block = False
                continue
            if ch == "\n":
                out.append("\n")
            i += 1
            continue
        if not in_sq and not in_dq:
            if code.startswith("=begin", i) and _line_start(code, i):
                in_block = True
                while i < n and code[i] != "\n":
                    i += 1
                continue
            if ch == "#":
                while i < n and code[i] != "\n":
                    i += 1
                continue
        if ch == "'" and not in_dq:
            in_sq = not in_sq
            out.append(ch)
            i += 1
            continue
        if ch == '"' and not in_sq:
            if in_dq and i > 0 and code[i - 1] == "\\":
                out.append(ch)
                i += 1
                continue
            in_dq = not in_dq
            out.append(ch)
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _line_start(code: str, i: int) -> bool:
    j = i - 1
    while j >= 0 and code[j] in " \t":
        j -= 1
    return j < 0 or code[j] == "\n"


def _scan(
    code: str,
    *,
    line_markers: tuple[str, ...],
    block_start: str | None,
    block_end: str | None,
    allow_triple: bool,
    backtick: bool,
    string_quotes: tuple[str, ...] = ('"', "'"),
) -> str:
    out: list[str] = []
    i = 0
    n = len(code)
    in_str: str | None = None
    escape = False

    while i < n:
        if in_str is not None:
            if escape:
                out.append(code[i])
                escape = False
                i += 1
                continue
            if code[i] == "\\" and in_str in {'"', "'", "`"}:
                out.append(code[i])
                escape = True
                i += 1
                continue
            if len(in_str) == 3:
                if code.startswith(in_str, i):
                    out.append(in_str)
                    i += 3
                    in_str = None
                    continue
                out.append(code[i])
                i += 1
                continue
            if code.startswith(in_str, i):
                out.append(in_str)
                i += len(in_str)
                in_str = None
                continue
            out.append(code[i])
            i += 1
            continue

        if allow_triple and (
            code.startswith('"""', i) or code.startswith("'''", i)
        ):
            in_str = code[i : i + 3]
            out.append(in_str)
            i += 3
            continue
        if code[i] in string_quotes:
            in_str = code[i]
            out.append(code[i])
            i += 1
            continue
        if backtick and code[i] == "`":
            in_str = "`"
            out.append("`")
            i += 1
            continue

        if block_start and block_end and code.startswith(block_start, i):
            i += len(block_start)
            while i < n and not code.startswith(block_end, i):
                if code[i] == "\n":
                    out.append("\n")
                i += 1
            if i < n:
                i += len(block_end)
            continue

        hit = False
        for marker in line_markers:
            if code.startswith(marker, i):
                while i < n and code[i] != "\n":
                    i += 1
                hit = True
                break
        if hit:
            continue

        out.append(code[i])
        i += 1

    return "".join(out)
