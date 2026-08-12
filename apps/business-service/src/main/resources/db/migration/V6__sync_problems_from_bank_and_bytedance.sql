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
