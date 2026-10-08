from app.coach.window import should_run_digest


def test_periodic_digest_runs_without_closing_session() -> None:
    assert should_run_digest(
        turn_count=6,
        close_scope="none",
        force=False,
        every_n=6,
    )


def test_digest_skips_ordinary_turn() -> None:
    assert not should_run_digest(
        turn_count=5,
        close_scope="none",
        force=False,
        every_n=6,
    )
