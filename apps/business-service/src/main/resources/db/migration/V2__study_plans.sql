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
