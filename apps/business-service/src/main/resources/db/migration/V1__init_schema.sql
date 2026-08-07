-- CodeArena / LeetMate business schema (PostgreSQL) — full baseline.
-- Replaces legacy incremental V1–V8. Wipe DB / flyway_schema_history before first apply.

-- =============================================================================
-- User domain
-- =============================================================================

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    public_id       VARCHAR(32)  NOT NULL,
    username        VARCHAR(64)  NOT NULL,
    display_name    VARCHAR(128),
    email           VARCHAR(255),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_public_id UNIQUE (public_id)
);

CREATE UNIQUE INDEX uq_users_email ON users(email) WHERE email IS NOT NULL;

CREATE TABLE user_profiles (
    user_id         BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    avatar_url      TEXT,
    bio             TEXT,
    locale          VARCHAR(16)  NOT NULL DEFAULT 'zh-CN',
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_identities (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        VARCHAR(32)  NOT NULL,
    provider_uid    VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_uid)
);

CREATE INDEX idx_user_identities_user ON user_identities(user_id);

CREATE TABLE user_credentials (
    user_id         BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash   VARCHAR(255),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_llm_settings (
    user_id         BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    provider        VARCHAR(16)  NOT NULL DEFAULT 'api',
    api_provider    VARCHAR(32)  NOT NULL DEFAULT 'deepseek',
    coach_model     VARCHAR(128) NOT NULL DEFAULT 'deepseek-chat',
    base_url        VARCHAR(512) NOT NULL DEFAULT '',
    api_key_enc     TEXT         NOT NULL DEFAULT '',
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_llm_settings_updated ON user_llm_settings (updated_at DESC);

CREATE TABLE auth_sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL,
    client          VARCHAR(32)  NOT NULL DEFAULT 'extension',
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT uq_auth_sessions_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_expires ON auth_sessions(expires_at);

-- =============================================================================
-- Problem / submission / stats
-- =============================================================================

CREATE TABLE problems (
    id              BIGSERIAL PRIMARY KEY,
    problem_id      INTEGER      NOT NULL UNIQUE,
    title           TEXT         NOT NULL,
    slug            TEXT         NOT NULL,
    difficulty      VARCHAR(16)  CHECK (difficulty IN ('Easy', 'Medium', 'Hard')),
    tags            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE submissions (
    id              BIGSERIAL PRIMARY KEY,
    submission_id   VARCHAR(64)  NOT NULL UNIQUE,
    problem_id      INTEGER      NOT NULL REFERENCES problems(problem_id),
    user_id         BIGINT REFERENCES users(id),
    status          TEXT         NOT NULL,
    code            TEXT,
    runtime_ms      INTEGER,
    memory_mb       DOUBLE PRECISION,
    language        VARCHAR(64),
    submitted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_submissions_problem_id ON submissions(problem_id);
CREATE INDEX idx_submissions_submitted_at ON submissions(submitted_at);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_submissions_user ON submissions(user_id);

CREATE TABLE problem_stats (
    problem_id          INTEGER PRIMARY KEY REFERENCES problems(problem_id),
    title               TEXT,
    title_slug          TEXT,
    difficulty          VARCHAR(16),
    topic_tags          TEXT,
    total_attempts      INTEGER NOT NULL DEFAULT 0,
    accepted_count      INTEGER NOT NULL DEFAULT 0,
    wrong_count         INTEGER NOT NULL DEFAULT 0,
    status_breakdown    TEXT,
    first_attempt_at    TIMESTAMPTZ,
    last_attempt_at     TIMESTAMPTZ,
    first_accepted_at   TIMESTAMPTZ,
    acceptance_rate     DOUBLE PRECISION NOT NULL DEFAULT 0,
    struggle_score      DOUBLE PRECISION NOT NULL DEFAULT 0,
    solve_time_seconds  INTEGER,
    avg_attempts_to_ac  DOUBLE PRECISION,
    attempts_at_last_ac INTEGER NOT NULL DEFAULT 0,
    last_status         TEXT,
    last_submitted_at   TIMESTAMPTZ,
    llm_summary         TEXT,
    common_pitfall      TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE problem_daily_stats (
    problem_id          INTEGER NOT NULL REFERENCES problems(problem_id),
    stat_day            DATE    NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    accepted_today      INTEGER NOT NULL DEFAULT 0,
    wrong_today         INTEGER NOT NULL DEFAULT 0,
    status_breakdown    TEXT,
    consecutive_days    INTEGER NOT NULL DEFAULT 0,
    is_new_today        BOOLEAN NOT NULL DEFAULT FALSE,
    is_review_today     BOOLEAN NOT NULL DEFAULT FALSE,
    status_change       VARCHAR(32),
    PRIMARY KEY (problem_id, stat_day)
);

CREATE INDEX idx_problem_daily_day ON problem_daily_stats(stat_day);

-- =============================================================================
-- Learning domain
-- =============================================================================

CREATE TABLE problem_lists (
    id              VARCHAR(64) PRIMARY KEY,
    name            TEXT NOT NULL,
    source          VARCHAR(32) NOT NULL DEFAULT 'user',
    readonly        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE problem_list_items (
    list_id         VARCHAR(64) NOT NULL REFERENCES problem_lists(id) ON DELETE CASCADE,
    problem_id      INTEGER NOT NULL,
    slug            TEXT NOT NULL,
    title           TEXT NOT NULL,
    difficulty      VARCHAR(16) NOT NULL,
    tags_json       TEXT NOT NULL DEFAULT '[]',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (list_id, problem_id)
);

CREATE INDEX idx_problem_list_items_order ON problem_list_items(list_id, sort_order);

CREATE TABLE learning_prefs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES users(id),
    list_mode       BOOLEAN NOT NULL DEFAULT TRUE,
    kg_mode         BOOLEAN NOT NULL DEFAULT TRUE,
    active_list_id  VARCHAR(64) NOT NULL DEFAULT 'hot100',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_learning_prefs_user ON learning_prefs(user_id);

CREATE TABLE user_problem_flags (
    user_id         BIGINT NOT NULL REFERENCES users(id),
    problem_id      INTEGER NOT NULL,
    mastered        BOOLEAN NOT NULL DEFAULT FALSE,
    mastered_at     TIMESTAMPTZ,
    note            TEXT,
    PRIMARY KEY (user_id, problem_id)
);

CREATE INDEX idx_user_problem_flags_user ON user_problem_flags(user_id);

-- =============================================================================
-- Team / pay stubs (modular monolith placeholders)
-- =============================================================================

CREATE TABLE team_rooms (
    id              BIGSERIAL PRIMARY KEY,
    public_id       VARCHAR(32)  NOT NULL UNIQUE,
    title           VARCHAR(128) NOT NULL,
    owner_user_id   BIGINT REFERENCES users(id),
    status          VARCHAR(16)  NOT NULL DEFAULT 'open',
    max_members     INTEGER      NOT NULL DEFAULT 8,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_team_rooms_status ON team_rooms(status);

CREATE TABLE team_members (
    id              BIGSERIAL PRIMARY KEY,
    room_id         BIGINT       NOT NULL REFERENCES team_rooms(id) ON DELETE CASCADE,
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    role            VARCHAR(16)  NOT NULL DEFAULT 'member',
    joined_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (room_id, user_id)
);

CREATE INDEX idx_team_members_user ON team_members(user_id);

CREATE TABLE pay_orders (
    id              BIGSERIAL PRIMARY KEY,
    public_id       VARCHAR(32)  NOT NULL UNIQUE,
    user_id         BIGINT REFERENCES users(id),
    amount_cents    INTEGER      NOT NULL DEFAULT 0,
    currency        VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    status          VARCHAR(16)  NOT NULL DEFAULT 'created',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pay_orders_user ON pay_orders(user_id);

-- =============================================================================
-- Coach memory (L2 sessions/turns + L3 long-term)
-- =============================================================================

CREATE TABLE coach_sessions (
    session_id      VARCHAR(64) PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    thread_id       VARCHAR(96)  NOT NULL,
    problem_id      INTEGER,
    submission_id   VARCHAR(128),
    mode            VARCHAR(32)  NOT NULL DEFAULT 'default',
    topic           VARCHAR(64)  NOT NULL DEFAULT '',
    session_kind    VARCHAR(16)  NOT NULL DEFAULT 'lobby',
    summary         TEXT         NOT NULL DEFAULT '',
    opening         TEXT         NOT NULL DEFAULT '',
    phase           VARCHAR(32)  NOT NULL DEFAULT 'lobby',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coach_sessions_user_updated
    ON coach_sessions (user_id, updated_at DESC);
CREATE INDEX idx_coach_sessions_user_submission
    ON coach_sessions (user_id, submission_id);
CREATE INDEX idx_coach_sessions_user_problem
    ON coach_sessions (user_id, problem_id);
CREATE INDEX idx_coach_sessions_user_topic
    ON coach_sessions (user_id, topic)
    WHERE topic <> '';
CREATE INDEX idx_coach_sessions_user_kind
    ON coach_sessions (user_id, session_kind, updated_at DESC);

CREATE TABLE coach_turns (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64)  NOT NULL REFERENCES coach_sessions(session_id) ON DELETE CASCADE,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT         NOT NULL DEFAULT '',
    intent          VARCHAR(64),
    phase           VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coach_turns_session_created
    ON coach_turns (session_id, created_at);

CREATE TABLE user_coach_memories (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind            VARCHAR(32)  NOT NULL,
    content         TEXT         NOT NULL,
    source          VARCHAR(16)  NOT NULL DEFAULT 'coach',
    problem_id      INTEGER,
    confidence      REAL         NOT NULL DEFAULT 0.8,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_coach_memories_user_active
    ON user_coach_memories (user_id, active, updated_at DESC);
CREATE INDEX idx_user_coach_memories_user_kind
    ON user_coach_memories (user_id, kind) WHERE active = TRUE;

-- =============================================================================
-- Seed data
-- =============================================================================

INSERT INTO problem_lists (id, name, source, readonly)
VALUES ('hot100', 'Hot 100', 'system', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO users (public_id, username, display_name, status)
VALUES ('usr_default00001', 'default', 'Default User', 'active')
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_profiles (user_id)
SELECT u.id FROM users u
WHERE u.username = 'default'
  AND NOT EXISTS (SELECT 1 FROM user_profiles p WHERE p.user_id = u.id);

INSERT INTO user_identities (user_id, provider, provider_uid)
SELECT u.id, 'local', u.username
FROM users u
WHERE u.username = 'default'
  AND NOT EXISTS (
      SELECT 1 FROM user_identities i
      WHERE i.provider = 'local' AND i.provider_uid = u.username
  );

INSERT INTO learning_prefs (user_id, list_mode, kg_mode, active_list_id)
SELECT u.id, TRUE, TRUE, 'hot100'
FROM users u
WHERE u.username = 'default'
  AND NOT EXISTS (SELECT 1 FROM learning_prefs lp WHERE lp.user_id = u.id);
