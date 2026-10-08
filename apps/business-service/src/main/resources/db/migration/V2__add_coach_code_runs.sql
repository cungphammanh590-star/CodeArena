-- V1 may already have been applied by a database volume created before the
-- code-run audit table was added to the baseline migration.
CREATE TABLE IF NOT EXISTS coach_code_runs (
  id            BIGSERIAL PRIMARY KEY,
  user_id       BIGINT NOT NULL REFERENCES users(id),
  session_id    VARCHAR(64) NOT NULL,
  problem_id    INT NULL,
  language      VARCHAR(16) NOT NULL,
  exit_code     INT NULL,
  timed_out     BOOLEAN NOT NULL DEFAULT FALSE,
  duration_ms   INT NULL,
  snippet_hash  VARCHAR(80) NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_coach_code_runs_user_session
  ON coach_code_runs(user_id, session_id);
