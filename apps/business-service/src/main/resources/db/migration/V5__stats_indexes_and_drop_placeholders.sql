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
DROP TABLE IF EXISTS team_members;
DROP TABLE IF EXISTS team_rooms;
DROP TABLE IF EXISTS pay_orders;
DROP TABLE IF EXISTS plan_notifications;
DROP TABLE IF EXISTS user_kp_mastery;
DROP TABLE IF EXISTS knowledge_points;
DROP TABLE IF EXISTS coach_code_runs;

-- Aggregates were never written by Submit; tools/UI now use submissions + problems
DROP TABLE IF EXISTS problem_daily_stats;
DROP TABLE IF EXISTS problem_stats;
