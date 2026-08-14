-- CodeArena business schema (PostgreSQL) — full baseline.
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
-- Study plans: goal-based problem lists ± daily schedule
-- =============================================================================

CREATE TABLE study_plans (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    goal_type       VARCHAR(32) NOT NULL,
    goal_ref        VARCHAR(128) NOT NULL,
    title           VARCHAR(256),
    list_id         VARCHAR(64) REFERENCES problem_lists(id),
    total_days      INTEGER,
    start_date      DATE,
    end_date        DATE,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_study_plans_user_status ON study_plans (user_id, status);
CREATE INDEX idx_study_plans_user_created ON study_plans (user_id, created_at DESC);

CREATE TABLE plan_daily_tasks (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         BIGINT NOT NULL REFERENCES study_plans(id) ON DELETE CASCADE,
    day_num         INTEGER NOT NULL,
    scheduled_date  DATE NOT NULL,
    problem_ids     TEXT NOT NULL DEFAULT '[]',
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    completed_at    TIMESTAMPTZ,
    UNIQUE (plan_id, day_num)
);

CREATE INDEX idx_plan_daily_tasks_date ON plan_daily_tasks (scheduled_date);
CREATE INDEX idx_plan_daily_tasks_plan_date ON plan_daily_tasks (plan_id, scheduled_date);

CREATE TABLE plan_notifications (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         BIGINT REFERENCES study_plans(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    day_num         INTEGER NOT NULL,
    scheduled_date  DATE NOT NULL,
    channel         VARCHAR(20) NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (plan_id, day_num, channel, scheduled_date)
);

-- Goal problem banks (company / topic / list seeds); metadata allows list creation
-- even when problems table is still sparse.
CREATE TABLE goal_problem_banks (
    goal_type       VARCHAR(32) NOT NULL,
    goal_ref        VARCHAR(128) NOT NULL,
    problem_id      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    slug            TEXT NOT NULL,
    difficulty      VARCHAR(16) NOT NULL DEFAULT 'Medium',
    stage_hint      VARCHAR(32),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (goal_type, goal_ref, problem_id)
);

CREATE INDEX idx_goal_banks_ref ON goal_problem_banks (goal_type, goal_ref, sort_order);

-- Google interview-ish classics
INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order) VALUES
('company', 'Google', 1, 'Two Sum', 'two-sum', 'Easy', 'foundation', 1),
('company', 'Google', 20, 'Valid Parentheses', 'valid-parentheses', 'Easy', 'foundation', 2),
('company', 'Google', 21, 'Merge Two Sorted Lists', 'merge-two-sorted-lists', 'Easy', 'foundation', 3),
('company', 'Google', 53, 'Maximum Subarray', 'maximum-subarray', 'Medium', 'foundation', 4),
('company', 'Google', 70, 'Climbing Stairs', 'climbing-stairs', 'Easy', 'foundation', 5),
('company', 'Google', 121, 'Best Time to Buy and Sell Stock', 'best-time-to-buy-and-sell-stock', 'Easy', 'foundation', 6),
('company', 'Google', 141, 'Linked List Cycle', 'linked-list-cycle', 'Easy', 'foundation', 7),
('company', 'Google', 206, 'Reverse Linked List', 'reverse-linked-list', 'Easy', 'foundation', 8),
('company', 'Google', 226, 'Invert Binary Tree', 'invert-binary-tree', 'Easy', 'foundation', 9),
('company', 'Google', 232, 'Implement Queue using Stacks', 'implement-queue-using-stacks', 'Easy', 'foundation', 10),
('company', 'Google', 3, 'Longest Substring Without Repeating Characters', 'longest-substring-without-repeating-characters', 'Medium', 'intensify', 11),
('company', 'Google', 15, '3Sum', '3sum', 'Medium', 'intensify', 12),
('company', 'Google', 33, 'Search in Rotated Sorted Array', 'search-in-rotated-sorted-array', 'Medium', 'intensify', 13),
('company', 'Google', 39, 'Combination Sum', 'combination-sum', 'Medium', 'intensify', 14),
('company', 'Google', 46, 'Permutations', 'permutations', 'Medium', 'intensify', 15),
('company', 'Google', 56, 'Merge Intervals', 'merge-intervals', 'Medium', 'intensify', 16),
('company', 'Google', 75, 'Sort Colors', 'sort-colors', 'Medium', 'intensify', 17),
('company', 'Google', 78, 'Subsets', 'subsets', 'Medium', 'intensify', 18),
('company', 'Google', 98, 'Validate Binary Search Tree', 'validate-binary-search-tree', 'Medium', 'intensify', 19),
('company', 'Google', 102, 'Binary Tree Level Order Traversal', 'binary-tree-level-order-traversal', 'Medium', 'intensify', 20),
('company', 'Google', 128, 'Longest Consecutive Sequence', 'longest-consecutive-sequence', 'Medium', 'intensify', 21),
('company', 'Google', 139, 'Word Break', 'word-break', 'Medium', 'intensify', 22),
('company', 'Google', 146, 'LRU Cache', 'lru-cache', 'Medium', 'intensify', 23),
('company', 'Google', 200, 'Number of Islands', 'number-of-islands', 'Medium', 'intensify', 24),
('company', 'Google', 207, 'Course Schedule', 'course-schedule', 'Medium', 'intensify', 25),
('company', 'Google', 215, 'Kth Largest Element in an Array', 'kth-largest-element-in-an-array', 'Medium', 'intensify', 26),
('company', 'Google', 238, 'Product of Array Except Self', 'product-of-array-except-self', 'Medium', 'intensify', 27),
('company', 'Google', 300, 'Longest Increasing Subsequence', 'longest-increasing-subsequence', 'Medium', 'intensify', 28),
('company', 'Google', 322, 'Coin Change', 'coin-change', 'Medium', 'intensify', 29),
('company', 'Google', 347, 'Top K Frequent Elements', 'top-k-frequent-elements', 'Medium', 'intensify', 30),
('company', 'Google', 4, 'Median of Two Sorted Arrays', 'median-of-two-sorted-arrays', 'Hard', 'mock', 31),
('company', 'Google', 23, 'Merge k Sorted Lists', 'merge-k-sorted-lists', 'Hard', 'mock', 32),
('company', 'Google', 42, 'Trapping Rain Water', 'trapping-rain-water', 'Hard', 'mock', 33),
('company', 'Google', 76, 'Minimum Window Substring', 'minimum-window-substring', 'Hard', 'mock', 34),
('company', 'Google', 84, 'Largest Rectangle in Histogram', 'largest-rectangle-in-histogram', 'Hard', 'mock', 35),
('company', 'Google', 124, 'Binary Tree Maximum Path Sum', 'binary-tree-maximum-path-sum', 'Hard', 'mock', 36),
('company', 'Google', 239, 'Sliding Window Maximum', 'sliding-window-maximum', 'Hard', 'mock', 37),
('company', 'Google', 295, 'Find Median from Data Stream', 'find-median-from-data-stream', 'Hard', 'mock', 38),
('company', 'Google', 297, 'Serialize and Deserialize Binary Tree', 'serialize-and-deserialize-binary-tree', 'Hard', 'mock', 39),
('company', 'Google', 329, 'Longest Increasing Path in a Matrix', 'longest-increasing-path-in-a-matrix', 'Hard', 'mock', 40);

-- Topic: 动态规划
INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order) VALUES
('topic', '动态规划', 70, 'Climbing Stairs', 'climbing-stairs', 'Easy', 'foundation', 1),
('topic', '动态规划', 118, 'Pascal''s Triangle', 'pascals-triangle', 'Easy', 'foundation', 2),
('topic', '动态规划', 119, 'Pascal''s Triangle II', 'pascals-triangle-ii', 'Easy', 'foundation', 3),
('topic', '动态规划', 121, 'Best Time to Buy and Sell Stock', 'best-time-to-buy-and-sell-stock', 'Easy', 'foundation', 4),
('topic', '动态规划', 198, 'House Robber', 'house-robber', 'Medium', 'foundation', 5),
('topic', '动态规划', 338, 'Counting Bits', 'counting-bits', 'Easy', 'foundation', 6),
('topic', '动态规划', 509, 'Fibonacci Number', 'fibonacci-number', 'Easy', 'foundation', 7),
('topic', '动态规划', 746, 'Min Cost Climbing Stairs', 'min-cost-climbing-stairs', 'Easy', 'foundation', 8),
('topic', '动态规划', 53, 'Maximum Subarray', 'maximum-subarray', 'Medium', 'intensify', 9),
('topic', '动态规划', 62, 'Unique Paths', 'unique-paths', 'Medium', 'intensify', 10),
('topic', '动态规划', 63, 'Unique Paths II', 'unique-paths-ii', 'Medium', 'intensify', 11),
('topic', '动态规划', 64, 'Minimum Path Sum', 'minimum-path-sum', 'Medium', 'intensify', 12),
('topic', '动态规划', 91, 'Decode Ways', 'decode-ways', 'Medium', 'intensify', 13),
('topic', '动态规划', 139, 'Word Break', 'word-break', 'Medium', 'intensify', 14),
('topic', '动态规划', 152, 'Maximum Product Subarray', 'maximum-product-subarray', 'Medium', 'intensify', 15),
('topic', '动态规划', 213, 'House Robber II', 'house-robber-ii', 'Medium', 'intensify', 16),
('topic', '动态规划', 221, 'Maximal Square', 'maximal-square', 'Medium', 'intensify', 17),
('topic', '动态规划', 279, 'Perfect Squares', 'perfect-squares', 'Medium', 'intensify', 18),
('topic', '动态规划', 300, 'Longest Increasing Subsequence', 'longest-increasing-subsequence', 'Medium', 'intensify', 19),
('topic', '动态规划', 322, 'Coin Change', 'coin-change', 'Medium', 'intensify', 20),
('topic', '动态规划', 416, 'Partition Equal Subset Sum', 'partition-equal-subset-sum', 'Medium', 'intensify', 21),
('topic', '动态规划', 494, 'Target Sum', 'target-sum', 'Medium', 'intensify', 22),
('topic', '动态规划', 518, 'Coin Change II', 'coin-change-ii', 'Medium', 'intensify', 23),
('topic', '动态规划', 72, 'Edit Distance', 'edit-distance', 'Medium', 'mock', 24),
('topic', '动态规划', 115, 'Distinct Subsequences', 'distinct-subsequences', 'Hard', 'mock', 25),
('topic', '动态规划', 123, 'Best Time to Buy and Sell Stock III', 'best-time-to-buy-and-sell-stock-iii', 'Hard', 'mock', 26),
('topic', '动态规划', 188, 'Best Time to Buy and Sell Stock IV', 'best-time-to-buy-and-sell-stock-iv', 'Hard', 'mock', 27),
('topic', '动态规划', 312, 'Burst Balloons', 'burst-balloons', 'Hard', 'mock', 28),
('topic', '动态规划', 410, 'Split Array Largest Sum', 'split-array-largest-sum', 'Hard', 'mock', 29);

-- Topic: 链表
INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order) VALUES
('topic', '链表', 21, 'Merge Two Sorted Lists', 'merge-two-sorted-lists', 'Easy', 'foundation', 1),
('topic', '链表', 83, 'Remove Duplicates from Sorted List', 'remove-duplicates-from-sorted-list', 'Easy', 'foundation', 2),
('topic', '链表', 141, 'Linked List Cycle', 'linked-list-cycle', 'Easy', 'foundation', 3),
('topic', '链表', 160, 'Intersection of Two Linked Lists', 'intersection-of-two-linked-lists', 'Easy', 'foundation', 4),
('topic', '链表', 203, 'Remove Linked List Elements', 'remove-linked-list-elements', 'Easy', 'foundation', 5),
('topic', '链表', 206, 'Reverse Linked List', 'reverse-linked-list', 'Easy', 'foundation', 6),
('topic', '链表', 234, 'Palindrome Linked List', 'palindrome-linked-list', 'Easy', 'foundation', 7),
('topic', '链表', 2, 'Add Two Numbers', 'add-two-numbers', 'Medium', 'intensify', 8),
('topic', '链表', 19, 'Remove Nth Node From End of List', 'remove-nth-node-from-end-of-list', 'Medium', 'intensify', 9),
('topic', '链表', 24, 'Swap Nodes in Pairs', 'swap-nodes-in-pairs', 'Medium', 'intensify', 10),
('topic', '链表', 61, 'Rotate List', 'rotate-list', 'Medium', 'intensify', 11),
('topic', '链表', 82, 'Remove Duplicates from Sorted List II', 'remove-duplicates-from-sorted-list-ii', 'Medium', 'intensify', 12),
('topic', '链表', 86, 'Partition List', 'partition-list', 'Medium', 'intensify', 13),
('topic', '链表', 92, 'Reverse Linked List II', 'reverse-linked-list-ii', 'Medium', 'intensify', 14),
('topic', '链表', 142, 'Linked List Cycle II', 'linked-list-cycle-ii', 'Medium', 'intensify', 15),
('topic', '链表', 143, 'Reorder List', 'reorder-list', 'Medium', 'intensify', 16),
('topic', '链表', 148, 'Sort List', 'sort-list', 'Medium', 'intensify', 17),
('topic', '链表', 23, 'Merge k Sorted Lists', 'merge-k-sorted-lists', 'Hard', 'mock', 18),
('topic', '链表', 25, 'Reverse Nodes in k-Group', 'reverse-nodes-in-k-group', 'Hard', 'mock', 19);

-- Hot100 list seed (also used by list goal_type); fill problem_list_items if empty
INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order)
SELECT 'list', 'hot100', problem_id, title, slug, difficulty, stage_hint, sort_order
FROM goal_problem_banks
WHERE goal_type = 'company' AND goal_ref = 'Google'
ON CONFLICT DO NOTHING;

INSERT INTO problem_list_items (list_id, problem_id, slug, title, difficulty, tags_json, sort_order)
SELECT 'hot100', problem_id, slug, title, difficulty, '[]', sort_order
FROM goal_problem_banks
WHERE goal_type = 'list' AND goal_ref = 'hot100'
ON CONFLICT DO NOTHING;
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
-- Per-user LLM usage events (for in-app “我的 Key 用量” — not Langfuse).

CREATE TABLE llm_usage_events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id      VARCHAR(64),
    request_id      VARCHAR(64),
    provider        VARCHAR(32)  NOT NULL DEFAULT '',
    api_provider    VARCHAR(32)  NOT NULL DEFAULT '',
    model           VARCHAR(128) NOT NULL DEFAULT '',
    prompt_tokens   INT          NOT NULL DEFAULT 0,
    completion_tokens INT        NOT NULL DEFAULT 0,
    total_tokens    INT          NOT NULL DEFAULT 0,
    success         BOOLEAN      NOT NULL DEFAULT TRUE,
    error_code      VARCHAR(64)  NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_llm_usage_user_created ON llm_usage_events (user_id, created_at DESC);
CREATE INDEX idx_llm_usage_session ON llm_usage_events (session_id) WHERE session_id IS NOT NULL;
-- P0: submission/mastery indexes; drop unused placeholder tables & dead aggregates.

-- Hot-path indexes for user stats / list progress / problem detail
CREATE INDEX IF NOT EXISTS idx_submissions_user_submitted
    ON submissions (user_id, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_submissions_user_accepted_problem
    ON submissions (user_id, problem_id)
    WHERE status = 'Accepted';

CREATE INDEX IF NOT EXISTS idx_submissions_user_problem_submitted
    ON submissions (user_id, problem_id, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_problem_flags_user_mastered
    ON user_problem_flags (user_id, problem_id)
    WHERE mastered = TRUE;

-- Placeholder / never-wired tables (APIs already removed or never implemented)
DROP TABLE IF EXISTS pay_orders;
DROP TABLE IF EXISTS plan_notifications;
DROP TABLE IF EXISTS user_kp_mastery;
DROP TABLE IF EXISTS knowledge_points;
DROP TABLE IF EXISTS coach_code_runs;

-- Aggregates were never written by Submit; tools/UI now use submissions + problems
DROP TABLE IF EXISTS problem_daily_stats;
DROP TABLE IF EXISTS problem_stats;
-- Sync goal_problem_banks metadata into problems so resolve/bind/plan can use LC ids
-- even before a user has submitted those problems.

INSERT INTO problems (problem_id, title, slug, difficulty, tags, created_at)
SELECT DISTINCT ON (b.problem_id)
    b.problem_id,
    b.title,
    b.slug,
    COALESCE(NULLIF(b.difficulty, ''), 'Medium'),
    '[]',
    NOW()
FROM goal_problem_banks b
WHERE b.problem_id IS NOT NULL
ORDER BY b.problem_id, b.sort_order
ON CONFLICT (problem_id) DO NOTHING;

-- ByteDance (字节) interview-ish classics for company goal_type
INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order) VALUES
('company', 'ByteDance', 3, 'Longest Substring Without Repeating Characters', 'longest-substring-without-repeating-characters', 'Medium', 'foundation', 1),
('company', 'ByteDance', 146, 'LRU Cache', 'lru-cache', 'Medium', 'foundation', 2),
('company', 'ByteDance', 215, 'Kth Largest Element in an Array', 'kth-largest-element-in-an-array', 'Medium', 'foundation', 3),
('company', 'ByteDance', 206, 'Reverse Linked List', 'reverse-linked-list', 'Easy', 'foundation', 4),
('company', 'ByteDance', 25, 'Reverse Nodes in k-Group', 'reverse-nodes-in-k-group', 'Hard', 'foundation', 5),
('company', 'ByteDance', 200, 'Number of Islands', 'number-of-islands', 'Medium', 'foundation', 6),
('company', 'ByteDance', 5, 'Longest Palindromic Substring', 'longest-palindromic-substring', 'Medium', 'foundation', 7),
('company', 'ByteDance', 15, '3Sum', '3sum', 'Medium', 'foundation', 8),
('company', 'ByteDance', 19, 'Remove Nth Node From End of List', 'remove-nth-node-from-end-of-list', 'Medium', 'foundation', 9),
('company', 'ByteDance', 300, 'Longest Increasing Subsequence', 'longest-increasing-subsequence', 'Medium', 'foundation', 10),
('company', 'ByteDance', 23, 'Merge k Sorted Lists', 'merge-k-sorted-lists', 'Hard', 'intensify', 11),
('company', 'ByteDance', 21, 'Merge Two Sorted Lists', 'merge-two-sorted-lists', 'Easy', 'intensify', 12),
('company', 'ByteDance', 141, 'Linked List Cycle', 'linked-list-cycle', 'Easy', 'intensify', 13),
('company', 'ByteDance', 148, 'Sort List', 'sort-list', 'Medium', 'intensify', 14),
('company', 'ByteDance', 92, 'Reverse Linked List II', 'reverse-linked-list-ii', 'Medium', 'intensify', 15),
('company', 'ByteDance', 2, 'Add Two Numbers', 'add-two-numbers', 'Medium', 'intensify', 16),
('company', 'ByteDance', 236, 'Lowest Common Ancestor of a Binary Tree', 'lowest-common-ancestor-of-a-binary-tree', 'Medium', 'intensify', 17),
('company', 'ByteDance', 103, 'Binary Tree Zigzag Level Order Traversal', 'binary-tree-zigzag-level-order-traversal', 'Medium', 'intensify', 18),
('company', 'ByteDance', 102, 'Binary Tree Level Order Traversal', 'binary-tree-level-order-traversal', 'Medium', 'intensify', 19),
('company', 'ByteDance', 199, 'Binary Tree Right Side View', 'binary-tree-right-side-view', 'Medium', 'intensify', 20),
('company', 'ByteDance', 72, 'Edit Distance', 'edit-distance', 'Medium', 'intensify', 21),
('company', 'ByteDance', 1143, 'Longest Common Subsequence', 'longest-common-subsequence', 'Medium', 'intensify', 22),
('company', 'ByteDance', 53, 'Maximum Subarray', 'maximum-subarray', 'Medium', 'intensify', 23),
('company', 'ByteDance', 42, 'Trapping Rain Water', 'trapping-rain-water', 'Hard', 'intensify', 24),
('company', 'ByteDance', 88, 'Merge Sorted Array', 'merge-sorted-array', 'Easy', 'intensify', 25),
('company', 'ByteDance', 20, 'Valid Parentheses', 'valid-parentheses', 'Easy', 'intensify', 26),
('company', 'ByteDance', 1, 'Two Sum', 'two-sum', 'Easy', 'intensify', 27),
('company', 'ByteDance', 46, 'Permutations', 'permutations', 'Medium', 'intensify', 28),
('company', 'ByteDance', 33, 'Search in Rotated Sorted Array', 'search-in-rotated-sorted-array', 'Medium', 'mock', 29),
('company', 'ByteDance', 4, 'Median of Two Sorted Arrays', 'median-of-two-sorted-arrays', 'Hard', 'mock', 30),
('company', 'ByteDance', 912, 'Sort an Array', 'sort-an-array', 'Medium', 'mock', 31),
('company', 'ByteDance', 415, 'Add Strings', 'add-strings', 'Easy', 'mock', 32),
('company', 'ByteDance', 165, 'Compare Version Numbers', 'compare-version-numbers', 'Medium', 'mock', 33),
('company', 'ByteDance', 902, 'Numbers At Most N Given Digit Set', 'numbers-at-most-n-given-digit-set', 'Hard', 'mock', 34)
ON CONFLICT DO NOTHING;

-- company aliases
INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order)
SELECT 'company', '字节', problem_id, title, slug, difficulty, stage_hint, sort_order
FROM goal_problem_banks
WHERE goal_type = 'company' AND goal_ref = 'ByteDance'
ON CONFLICT DO NOTHING;

INSERT INTO goal_problem_banks (goal_type, goal_ref, problem_id, title, slug, difficulty, stage_hint, sort_order)
SELECT 'company', '字节跳动', problem_id, title, slug, difficulty, stage_hint, sort_order
FROM goal_problem_banks
WHERE goal_type = 'company' AND goal_ref = 'ByteDance'
ON CONFLICT DO NOTHING;

-- Re-sync any newly inserted bank rows into problems
INSERT INTO problems (problem_id, title, slug, difficulty, tags, created_at)
SELECT DISTINCT ON (b.problem_id)
    b.problem_id,
    b.title,
    b.slug,
    COALESCE(NULLIF(b.difficulty, ''), 'Medium'),
    '[]',
    NOW()
FROM goal_problem_banks b
WHERE b.problem_id IS NOT NULL
ORDER BY b.problem_id, b.sort_order
ON CONFLICT (problem_id) DO NOTHING;
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
-- User private knowledge base: documents → knowledge points → embedding refs.
-- Qdrant is a rebuildable index; Postgres remains source of truth.

CREATE TABLE IF NOT EXISTS kb_documents (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    title           VARCHAR(256) NOT NULL,
    source_type     VARCHAR(16) NOT NULL,
    content_hash    VARCHAR(64),
    storage_path    VARCHAR(512),
    raw_text        TEXT,
    cleaned_text    TEXT,
    status          VARCHAR(32) NOT NULL,
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_kb_documents_source_type
        CHECK (source_type IN ('text', 'markdown', 'pdf')),
    CONSTRAINT chk_kb_documents_status
        CHECK (status IN (
            'uploaded', 'parsing', 'cleaning', 'extracting',
            'embedding', 'ready', 'failed'
        ))
);

CREATE INDEX IF NOT EXISTS idx_kb_documents_user_created
    ON kb_documents (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_kb_documents_user_status
    ON kb_documents (user_id, status);

CREATE TABLE IF NOT EXISTS kb_knowledge_points (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES kb_documents(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    title           VARCHAR(512) NOT NULL,
    body            TEXT NOT NULL,
    topic           VARCHAR(128),
    tags_json       TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'ready',
    version         INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_kb_kp_status
        CHECK (status IN ('ready', 'deleted'))
);

CREATE INDEX IF NOT EXISTS idx_kb_kp_user_created
    ON kb_knowledge_points (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_kb_kp_document
    ON kb_knowledge_points (document_id);

CREATE INDEX IF NOT EXISTS idx_kb_kp_user_topic
    ON kb_knowledge_points (user_id, topic)
    WHERE status = 'ready';

CREATE TABLE IF NOT EXISTS kb_embeddings (
    id                   BIGSERIAL PRIMARY KEY,
    knowledge_point_id   BIGINT NOT NULL REFERENCES kb_knowledge_points(id) ON DELETE CASCADE,
    embedding_model      VARCHAR(128) NOT NULL,
    embedding_version    VARCHAR(64) NOT NULL,
    qdrant_point_id      VARCHAR(64) NOT NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_kb_emb_status
        CHECK (status IN ('active', 'stale')),
    CONSTRAINT uq_kb_emb_point UNIQUE (qdrant_point_id)
);

CREATE INDEX IF NOT EXISTS idx_kb_emb_kp
    ON kb_embeddings (knowledge_point_id);

CREATE INDEX IF NOT EXISTS idx_kb_emb_active
    ON kb_embeddings (knowledge_point_id, status)
    WHERE status = 'active';
-- KP refine fields + knowledge-point spaced repetition (flashcards).

ALTER TABLE kb_knowledge_points
    ADD COLUMN IF NOT EXISTS question TEXT,
    ADD COLUMN IF NOT EXISTS answer TEXT,
    ADD COLUMN IF NOT EXISTS key_points_json TEXT,
    ADD COLUMN IF NOT EXISTS refined BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS user_kp_srs (
    user_id              BIGINT NOT NULL REFERENCES users(id),
    knowledge_point_id   BIGINT NOT NULL REFERENCES kb_knowledge_points(id) ON DELETE CASCADE,
    ease                 REAL NOT NULL DEFAULT 2.5,
    interval_days        INT NOT NULL DEFAULT 0,
    reps                 INT NOT NULL DEFAULT 0,
    lapses               INT NOT NULL DEFAULT 0,
    due_at               TIMESTAMPTZ NOT NULL,
    last_outcome         VARCHAR(16),
    last_reviewed_at     TIMESTAMPTZ,
    suspended            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, knowledge_point_id)
);

CREATE INDEX IF NOT EXISTS idx_user_kp_srs_due
    ON user_kp_srs (user_id, due_at)
    WHERE suspended = FALSE;
-- First-run learning choices. These are preferences, not a second user identity.

ALTER TABLE user_profiles
    ADD COLUMN IF NOT EXISTS onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS learning_goal VARCHAR(32),
    ADD COLUMN IF NOT EXISTS daily_minutes INTEGER,
    ADD COLUMN IF NOT EXISTS learning_start_mode VARCHAR(32);
-- Account deletion is immediate and physical. Every user-owned relational row
-- must therefore follow the users row instead of blocking or becoming orphaned.

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS submissions_user_id_fkey;
ALTER TABLE submissions ADD CONSTRAINT submissions_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE learning_prefs DROP CONSTRAINT IF EXISTS learning_prefs_user_id_fkey;
ALTER TABLE learning_prefs ADD CONSTRAINT learning_prefs_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_problem_flags DROP CONSTRAINT IF EXISTS user_problem_flags_user_id_fkey;
ALTER TABLE user_problem_flags ADD CONSTRAINT user_problem_flags_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE study_plans DROP CONSTRAINT IF EXISTS study_plans_user_id_fkey;
ALTER TABLE study_plans ADD CONSTRAINT study_plans_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_problem_srs DROP CONSTRAINT IF EXISTS user_problem_srs_user_id_fkey;
ALTER TABLE user_problem_srs ADD CONSTRAINT user_problem_srs_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE kb_documents DROP CONSTRAINT IF EXISTS kb_documents_user_id_fkey;
ALTER TABLE kb_documents ADD CONSTRAINT kb_documents_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE kb_knowledge_points DROP CONSTRAINT IF EXISTS kb_knowledge_points_user_id_fkey;
ALTER TABLE kb_knowledge_points ADD CONSTRAINT kb_knowledge_points_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_kp_srs DROP CONSTRAINT IF EXISTS user_kp_srs_user_id_fkey;
ALTER TABLE user_kp_srs ADD CONSTRAINT user_kp_srs_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
