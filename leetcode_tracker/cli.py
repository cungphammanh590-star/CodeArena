"""leetcode-tracker CLI 入口。"""

from __future__ import annotations

import argparse
import json
import sys

from leetcode_tracker import __version__
from leetcode_tracker.infra.autostart import clean_logs, install_autostart, uninstall_autostart
from leetcode_tracker.coach.deps import coach_dependencies_available, coach_import_error_message
from leetcode_tracker.core.problem_stats import ensure_stats_materialized, get_llm_context, rebuild_stats
from leetcode_tracker.core.stats import format_stats_text, get_overview
from leetcode_tracker.infra.config import CONFIG_KEYS, load_config, mask_config_for_display, set_config_value
from leetcode_tracker.infra.db import init_db
from leetcode_tracker.infra.paths import db_path
from leetcode_tracker.kg.import_maps import get_kg_status, import_maps
from leetcode_tracker.kg.queries import format_kg_context_markdown, list_track_progress
from leetcode_tracker.server import run_server


def register_kg_parser(sub: argparse._SubParsersAction) -> None:
    kg = sub.add_parser("kg", help="知识图谱（algorithm-stone）")
    kg_sub = kg.add_subparsers(dest="kg_command", required=True)
    kg_sub.add_parser("import", help="从 bundled map 导入图谱")
    kg_sub.add_parser("status", help="查看图谱导入状态")
    prog = kg_sub.add_parser("progress", help="按路线查看子模块进度")
    prog.add_argument("--track", default=None, help="路线 id，如 dp")
    ctx = kg_sub.add_parser("context", help="输出单题图谱上下文（Markdown）")
    ctx.add_argument("problem_id", type=int)


def register_coach_parser(sub: argparse._SubParsersAction) -> None:
    coach = sub.add_parser("coach", help="刷题陪练（需 [coach] 依赖）")
    coach_sub = coach.add_subparsers(dest="coach_command", required=True)
    follow = coach_sub.add_parser("follow", help="围绕单次提交陪练")
    follow.add_argument("submission_id")
    coach_sub.add_parser("debrief", help="今日复盘开场")
    chat = coach_sub.add_parser("chat", help="交互式陪练（需 submission_id）")
    chat.add_argument("submission_id")


def cmd_kg(args: argparse.Namespace) -> int:
    conn = init_db()
    try:
        if args.kg_command == "import":
            result = import_maps(conn)
            print(
                f"Imported {result['tracks']} tracks, "
                f"{result['nodes']} nodes, "
                f"{result['problems']} problems, "
                f"{result['edges']} edges"
            )
            return 0
        if args.kg_command == "status":
            import json

            print(json.dumps(get_kg_status(conn), ensure_ascii=False, indent=2))
            return 0
        if args.kg_command == "progress":
            ensure_stats_materialized(conn)
            rows = list_track_progress(conn, args.track)
            for track in rows:
                print(f"\n## {track['track_name']} ({track['track_id']})")
                for node in track["nodes"]:
                    print(
                        f"  - {node['name']}: {node['accepted']}/{node['total']} AC "
                        f"({node['acceptance_rate']:.0%}), struggle≈{node['avg_struggle']}"
                    )
            return 0
        if args.kg_command == "context":
            ensure_stats_materialized(conn)
            md, _ = format_kg_context_markdown(conn, args.problem_id)
            sys.stdout.write(md)
            if not md.endswith("\n"):
                sys.stdout.write("\n")
            return 0
    finally:
        conn.close()
    return 2


def cmd_coach(args: argparse.Namespace) -> int:
    if not coach_dependencies_available():
        print(coach_import_error_message(), file=sys.stderr)
        return 2
    from leetcode_tracker.coach import service as coach_service

    conn = init_db()
    try:
        ensure_stats_materialized(conn)
        if args.coach_command == "debrief":
            print(coach_service.debrief_today(conn))
            return 0
        if args.coach_command == "follow":
            # prepare 只建模板会话；模型在用户发送消息时调用。
            result = coach_service.prepare(conn, args.submission_id)
            print(result["opening"])
            src = result.get("opening_source") or "?"
            print(f"\n[session_id={result['session_id']} opening_source={src}]")
            return 0
        if args.coach_command == "chat":
            # 同步 chat 复用 LangGraph 流；Web 页走 SSE /api/coach/stream。
            result = coach_service.prepare(conn, args.submission_id)
            print(result["opening"])
            print(
                f"\n[session_id={result['session_id']}] "
                "输入消息，空行或 Ctrl-D 结束。（CLI 同步；浏览器走 SSE）\n"
            )
            session_id = result["session_id"]
            while True:
                try:
                    line = input("你> ").strip()
                except EOFError:
                    break
                if not line:
                    break
                reply = coach_service.chat(conn, session_id, line)
                print(f"\n陪练> {reply['reply']}\n")
                if reply.get("done"):
                    break
            return 0
    finally:
        conn.close()
    return 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="leetcode-tracker",
        description="力扣刷题追踪与陪练（leetcode.cn）",
    )
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    serve = sub.add_parser("serve", help="启动服务与网页仪表盘")
    serve.add_argument("--host", default=None, help="默认读配置")
    serve.add_argument("--port", type=int, default=None, help="默认读配置")

    sub.add_parser("stats", help="打印刷题统计概览")

    rebuild = sub.add_parser("rebuild-stats", help="从 submissions 重建题目汇总表")
    rebuild.add_argument(
        "--incremental",
        action="store_true",
        help="不清空汇总表（默认全量重建）",
    )

    llm = sub.add_parser("llm-context", help="输出单题 LLM 分析上下文（Markdown）")
    llm.add_argument("problem_id", type=int, help="题号，如 560")

    logs = sub.add_parser("logs", help="管理自启服务日志")
    logs_sub = logs.add_subparsers(dest="logs_command", required=True)
    logs_sub.add_parser("clean", help="清空 leetcode-tracker 服务日志")

    config_p = sub.add_parser("config", help="查看或修改本机配置")
    config_sub = config_p.add_subparsers(dest="config_command", required=True)
    config_sub.add_parser("show", help="显示当前配置")
    config_set = config_sub.add_parser("set", help="设置配置项")
    config_set.add_argument("key")
    config_set.add_argument("value")

    register_kg_parser(sub)
    register_coach_parser(sub)

    auto = sub.add_parser("autostart", help="macOS 开机自启 serve")
    auto_sub = auto.add_subparsers(dest="autostart_command", required=True)
    auto_sub.add_parser("install", help="安装 LaunchAgent")
    auto_sub.add_parser("uninstall", help="卸载 LaunchAgent")
    return parser


def cmd_serve(args: argparse.Namespace) -> int:
    run_server(host=args.host, port=args.port)
    return 0


def cmd_stats(_: argparse.Namespace) -> int:
    conn = init_db()
    try:
        stats = get_overview(conn)
        sys.stdout.write(format_stats_text(stats))
    finally:
        conn.close()
    return 0


def cmd_logs(args: argparse.Namespace) -> int:
    if args.logs_command == "clean":
        removed = clean_logs()
        if not removed:
            print("没有可删除的日志文件")
            return 0
        for path in removed:
            print(f"Removed {path}")
        return 0
    return 2


def cmd_config(args: argparse.Namespace) -> int:
    if args.config_command == "show":
        cfg = mask_config_for_display(load_config())
        payload = dict(cfg)
        payload["db_path_readonly"] = str(db_path())
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    if args.config_command == "set":
        if args.key not in CONFIG_KEYS:
            print(f"不支持的配置项: {args.key}", file=sys.stderr)
            print(f"可用: {', '.join(CONFIG_KEYS)}", file=sys.stderr)
            return 2
        try:
            cfg = set_config_value(args.key, args.value)
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        print(json.dumps(mask_config_for_display(cfg), ensure_ascii=False, indent=2))
        return 0
    return 2


def cmd_autostart(args: argparse.Namespace) -> int:
    if args.autostart_command == "install":
        path = install_autostart()
        print(f"Installed LaunchAgent: {path}")
        return 0
    if args.autostart_command == "uninstall":
        uninstall_autostart()
        print("Uninstalled LaunchAgent (if present)")
        return 0
    return 2


def cmd_rebuild_stats(args: argparse.Namespace) -> int:
    conn = init_db()
    try:
        count = rebuild_stats(conn, from_scratch=not args.incremental)
    finally:
        conn.close()
    print(f"Rebuilt stats for {count} problem(s)")
    return 0


def cmd_llm_context(args: argparse.Namespace) -> int:
    conn = init_db()
    try:
        ensure_stats_materialized(conn)
        text = get_llm_context(conn, args.problem_id)
    finally:
        conn.close()
    sys.stdout.write(text)
    if not text.endswith("\n"):
        sys.stdout.write("\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    dispatch = {
        "serve": cmd_serve,
        "stats": cmd_stats,
        "logs": cmd_logs,
        "config": cmd_config,
        "autostart": cmd_autostart,
        "rebuild-stats": cmd_rebuild_stats,
        "llm-context": cmd_llm_context,
        "kg": cmd_kg,
        "coach": cmd_coach,
    }
    handler = dispatch.get(args.command)
    if not handler:
        parser.error(f"unknown command: {args.command}")
        return 2
    return handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
