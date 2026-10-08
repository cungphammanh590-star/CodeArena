-- Evidence-aware, consolidating coach memories. Existing rows remain readable.

ALTER TABLE user_coach_memories
    ADD COLUMN IF NOT EXISTS memory_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS evidence_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_evidence TEXT,
    ADD COLUMN IF NOT EXISTS source_session_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

UPDATE user_coach_memories
SET last_confirmed_at = COALESCE(last_confirmed_at, updated_at, created_at)
WHERE last_confirmed_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_coach_memories_active_key
    ON user_coach_memories (user_id, kind, memory_key)
    WHERE active = TRUE AND memory_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_coach_memories_recall
    ON user_coach_memories (user_id, problem_id, updated_at DESC)
    WHERE active = TRUE;
