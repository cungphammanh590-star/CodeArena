from app.observability.agent_trace import _safe, result_status, result_summary


def test_safe_redacts_sensitive_nested_fields() -> None:
    value = _safe({"query": "two sum", "history": ["private"], "api_key": "secret"})

    assert value == {
        "query": "two sum",
        "history": "<redacted>",
        "api_key": "<redacted>",
    }


def test_result_summary_is_bounded_and_redacted() -> None:
    summary = result_summary({"ok": False, "error": "x" * 800, "content": "private"})

    assert summary["ok"] is False
    assert len(summary["error"]) == 500
    assert "content" not in summary


def test_result_status_recognizes_error_envelopes() -> None:
    assert result_status('{"ok": false, "error": "failed"}') == "error"
    assert result_status({"ok": True, "items": []}) == "ok"
    assert result_status("plain text") == "ok"
