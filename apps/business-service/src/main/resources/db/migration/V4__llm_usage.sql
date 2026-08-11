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
