"""Local 优化：结构特征向量对比（严禁把历史 AC 源码注入 Prompt）。

红线（实现约束，勿删）：
1. 允许：用 ast（或启发式）从用户代码与历史 AC 各提取特征向量，做数值/结构对比。
2. 禁止：把历史 AC 的 source_code 字符串拼进发给 7B 的 system/user prompt
   （除非用户显式走看思路 / answer_egress）。
3. 输出：模型只能基于 answer_skeletons 陷阱文案 + 结构对比结论生成回复，
   全程不泄露具体 AC 代码行。
"""

from __future__ import annotations

import ast
import re
from typing import Any, Optional


def extract_code_features(source: str, *, language: str = "python") -> dict[str, Any]:
    """从源码提取结构特征；解析失败时回退正则启发式。不含源码本身。"""
    src = source or ""
    lang = (language or "").lower()
    features: dict[str, Any] = {
        "loop_nesting_max": 0,
        "has_recursion": False,
        "import_kinds": [],
        "line_count": len([ln for ln in src.splitlines() if ln.strip()]),
        "parse_ok": False,
    }
    if not src.strip():
        return features

    if "python" in lang or lang in {"", "py", "python3"}:
        try:
            tree = ast.parse(src)
            features["parse_ok"] = True
            features.update(_walk_python(tree))
            return features
        except SyntaxError:
            pass

    # 非 Python 或解析失败：启发式
    features["loop_nesting_max"] = _heuristic_loop_nesting(src)
    features["has_recursion"] = bool(
        re.search(r"\bfunction\s+(\w+)[\s\S]*?\b\1\s*\(", src)
    ) or ("递归" in src)
    features["import_kinds"] = _heuristic_imports(src)
    return features


def _walk_python(tree: ast.AST) -> dict[str, Any]:
    max_nest = 0
    imports: list[str] = []
    func_names: set[str] = set()

    class FuncCollector(ast.NodeVisitor):
        def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
            func_names.add(node.name)
            self.generic_visit(node)

        def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
            func_names.add(node.name)
            self.generic_visit(node)

    FuncCollector().visit(tree)

    class Walker(ast.NodeVisitor):
        def __init__(self) -> None:
            self.loop_depth = 0
            self.has_recursion = False

        def _enter_loop(self, node: ast.AST) -> None:
            self.loop_depth += 1
            nonlocal max_nest
            max_nest = max(max_nest, self.loop_depth)
            self.generic_visit(node)
            self.loop_depth -= 1

        def visit_For(self, node: ast.For) -> None:
            self._enter_loop(node)

        def visit_While(self, node: ast.While) -> None:
            self._enter_loop(node)

        def visit_ListComp(self, node: ast.ListComp) -> None:
            self._enter_loop(node)

        def visit_Call(self, node: ast.Call) -> None:
            if isinstance(node.func, ast.Name) and node.func.id in func_names:
                self.has_recursion = True
            self.generic_visit(node)

        def visit_Import(self, node: ast.Import) -> None:
            for alias in node.names:
                name = (alias.name or "").split(".")[0]
                if name and name not in imports:
                    imports.append(name)

        def visit_ImportFrom(self, node: ast.ImportFrom) -> None:
            name = (node.module or "").split(".")[0]
            if name and name not in imports:
                imports.append(name)

    w = Walker()
    w.visit(tree)
    return {
        "loop_nesting_max": max_nest,
        "has_recursion": w.has_recursion,
        "import_kinds": imports[:12],
    }


def _heuristic_loop_nesting(src: str) -> int:
    max_depth = 0
    depth = 0
    for line in src.splitlines():
        if re.search(r"\b(for|while)\b", line):
            depth += 1
            max_depth = max(max_depth, depth)
        if "}" in line or re.match(r"^\s*$", line):
            depth = max(0, depth - line.count("}"))
    # Python 风格：按缩进粗估
    indent_stack: list[int] = []
    for line in src.splitlines():
        if not line.strip() or not re.search(r"\b(for|while)\b", line):
            continue
        indent = len(line) - len(line.lstrip(" "))
        while indent_stack and indent <= indent_stack[-1]:
            indent_stack.pop()
        indent_stack.append(indent)
        max_depth = max(max_depth, len(indent_stack))
    return max_depth


def _heuristic_imports(src: str) -> list[str]:
    found: list[str] = []
    for m in re.finditer(r"(?:import|from)\s+([a-zA-Z_][\w.]*)", src):
        name = m.group(1).split(".")[0]
        if name and name not in found:
            found.append(name)
    return found[:12]


def compare_features(
    user: dict[str, Any],
    reference: Optional[dict[str, Any]],
) -> dict[str, Any]:
    """只返回结论字段，绝不携带源码。"""
    ref = reference or {}
    conclusions: list[str] = []
    u_loop = int(user.get("loop_nesting_max") or 0)
    r_loop = int(ref.get("loop_nesting_max") or 0) if ref else 0
    if ref and r_loop > 0 and u_loop > r_loop:
        conclusions.append(
            f"该题历史 AC 结构多为约 {r_loop} 层循环，你的代码约 {u_loop} 层；"
            "可考虑用哈希/双指针/预处理降低嵌套。"
        )
    elif u_loop >= 3:
        conclusions.append(
            f"你的代码循环嵌套约 {u_loop} 层，常见优化方向是哈希表、双指针或前缀结构。"
        )
    if user.get("has_recursion") and ref and not ref.get("has_recursion"):
        conclusions.append("你用了递归，而该题历史 AC 多为迭代；可检查是否可用栈/递推改写。")
    elif user.get("has_recursion"):
        conclusions.append("检测到递归；注意栈深度与重复子问题，考虑记忆化或改迭代。")
    u_imp = set(user.get("import_kinds") or [])
    r_imp = set(ref.get("import_kinds") or [])
    if ref and r_imp - u_imp:
        conclusions.append(
            "历史 AC 常见库/模块类型："
            + "、".join(sorted(r_imp - u_imp)[:5])
            + "（仅作方向提示，非要求照抄）。"
        )
    if not conclusions:
        conclusions.append("结构上未见明显多层嵌套异常；可结合题意检查边界与状态更新。")
    return {
        "user_loop_nesting_max": u_loop,
        "ref_loop_nesting_max": r_loop if ref else None,
        "conclusions": conclusions,
        # 明确不含源码
        "has_reference_features": bool(ref),
    }


def format_structure_conclusions(comparison: dict[str, Any]) -> str:
    lines = ["## 结构对比结论（无 AC 源码）"]
    for c in comparison.get("conclusions") or []:
        lines.append(f"- {c}")
    return "\n".join(lines)
