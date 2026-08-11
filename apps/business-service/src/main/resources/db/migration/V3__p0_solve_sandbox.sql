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

-- P1 mastery 占位：P0 不读写
CREATE TABLE IF NOT EXISTS knowledge_points (
  kp_id          VARCHAR(64) PRIMARY KEY,
  topic_tag      VARCHAR(64) NOT NULL,
  title          VARCHAR(256) NOT NULL,
  knowledge_type VARCHAR(32) NOT NULL,
  parent_id      VARCHAR(64) NULL
);

CREATE TABLE IF NOT EXISTS user_kp_mastery (
  user_id       BIGINT NOT NULL REFERENCES users(id),
  kp_id         VARCHAR(64) NOT NULL REFERENCES knowledge_points(kp_id),
  mastery       REAL NOT NULL DEFAULT 0,
  qualitative   BOOLEAN NULL,
  reps          INT NOT NULL DEFAULT 0,
  ease          REAL NOT NULL DEFAULT 2.5,
  interval_days INT NOT NULL DEFAULT 0,
  due_at        TIMESTAMPTZ NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, kp_id)
);
