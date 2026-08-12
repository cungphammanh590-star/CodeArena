-- Problem-level spaced repetition (SM-2 style). Mastered flags suspend cards.

CREATE TABLE IF NOT EXISTS user_problem_srs (
    user_id           BIGINT NOT NULL REFERENCES users(id),
    problem_id        INTEGER NOT NULL,
    ease              REAL NOT NULL DEFAULT 2.5,
    interval_days     INT NOT NULL DEFAULT 0,
    reps              INT NOT NULL DEFAULT 0,
    lapses            INT NOT NULL DEFAULT 0,
    due_at            TIMESTAMPTZ NOT NULL,
    last_outcome      VARCHAR(16),
    last_reviewed_at  TIMESTAMPTZ,
    suspended         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, problem_id)
);

CREATE INDEX IF NOT EXISTS idx_user_problem_srs_due
    ON user_problem_srs (user_id, due_at)
    WHERE suspended = FALSE;
