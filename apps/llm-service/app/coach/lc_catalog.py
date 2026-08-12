"""内置力扣题目录：DB/bank 未命中时，plan_resolve 用此补齐题号元数据。"""

from __future__ import annotations

from typing import Any, Optional

# problem_id -> {title, slug, difficulty, title_zh?}
# 覆盖 Google/DP/链表 bank + 字节高频 + 若干 Hot 常见题
_LC_ENTRIES: list[tuple[int, str, str, str, str]] = [
    # id, title_en, slug, difficulty, title_zh
    (1, "Two Sum", "two-sum", "Easy", "两数之和"),
    (2, "Add Two Numbers", "add-two-numbers", "Medium", "两数相加"),
    (3, "Longest Substring Without Repeating Characters", "longest-substring-without-repeating-characters", "Medium", "无重复字符的最长子串"),
    (4, "Median of Two Sorted Arrays", "median-of-two-sorted-arrays", "Hard", "寻找两个正序数组的中位数"),
    (5, "Longest Palindromic Substring", "longest-palindromic-substring", "Medium", "最长回文子串"),
    (15, "3Sum", "3sum", "Medium", "三数之和"),
    (19, "Remove Nth Node From End of List", "remove-nth-node-from-end-of-list", "Medium", "删除链表的倒数第 N 个结点"),
    (20, "Valid Parentheses", "valid-parentheses", "Easy", "有效的括号"),
    (21, "Merge Two Sorted Lists", "merge-two-sorted-lists", "Easy", "合并两个有序链表"),
    (22, "Generate Parentheses", "generate-parentheses", "Medium", "括号生成"),
    (23, "Merge k Sorted Lists", "merge-k-sorted-lists", "Hard", "合并 K 个升序链表"),
    (24, "Swap Nodes in Pairs", "swap-nodes-in-pairs", "Medium", "两两交换链表中的节点"),
    (25, "Reverse Nodes in k-Group", "reverse-nodes-in-k-group", "Hard", "K 个一组翻转链表"),
    (33, "Search in Rotated Sorted Array", "search-in-rotated-sorted-array", "Medium", "搜索旋转排序数组"),
    (34, "Find First and Last Position of Element in Sorted Array", "find-first-and-last-position-of-element-in-sorted-array", "Medium", "在排序数组中查找元素的第一个和最后一个位置"),
    (39, "Combination Sum", "combination-sum", "Medium", "组合总和"),
    (42, "Trapping Rain Water", "trapping-rain-water", "Hard", "接雨水"),
    (46, "Permutations", "permutations", "Medium", "全排列"),
    (53, "Maximum Subarray", "maximum-subarray", "Medium", "最大子数组和"),
    (56, "Merge Intervals", "merge-intervals", "Medium", "合并区间"),
    (70, "Climbing Stairs", "climbing-stairs", "Easy", "爬楼梯"),
    (72, "Edit Distance", "edit-distance", "Medium", "编辑距离"),
    (75, "Sort Colors", "sort-colors", "Medium", "颜色分类"),
    (76, "Minimum Window Substring", "minimum-window-substring", "Hard", "最小覆盖子串"),
    (78, "Subsets", "subsets", "Medium", "子集"),
    (84, "Largest Rectangle in Histogram", "largest-rectangle-in-histogram", "Hard", "柱状图中最大的矩形"),
    (88, "Merge Sorted Array", "merge-sorted-array", "Easy", "合并两个有序数组"),
    (92, "Reverse Linked List II", "reverse-linked-list-ii", "Medium", "反转链表 II"),
    (94, "Binary Tree Inorder Traversal", "binary-tree-inorder-traversal", "Easy", "二叉树的中序遍历"),
    (98, "Validate Binary Search Tree", "validate-binary-search-tree", "Medium", "验证二叉搜索树"),
    (102, "Binary Tree Level Order Traversal", "binary-tree-level-order-traversal", "Medium", "二叉树的层序遍历"),
    (103, "Binary Tree Zigzag Level Order Traversal", "binary-tree-zigzag-level-order-traversal", "Medium", "二叉树的锯齿形层序遍历"),
    (104, "Maximum Depth of Binary Tree", "maximum-depth-of-binary-tree", "Easy", "二叉树的最大深度"),
    (121, "Best Time to Buy and Sell Stock", "best-time-to-buy-and-sell-stock", "Easy", "买卖股票的最佳时机"),
    (122, "Best Time to Buy and Sell Stock II", "best-time-to-buy-and-sell-stock-ii", "Medium", "买卖股票的最佳时机 II"),
    (124, "Binary Tree Maximum Path Sum", "binary-tree-maximum-path-sum", "Hard", "二叉树中的最大路径和"),
    (128, "Longest Consecutive Sequence", "longest-consecutive-sequence", "Medium", "最长连续序列"),
    (139, "Word Break", "word-break", "Medium", "单词拆分"),
    (141, "Linked List Cycle", "linked-list-cycle", "Easy", "环形链表"),
    (142, "Linked List Cycle II", "linked-list-cycle-ii", "Medium", "环形链表 II"),
    (146, "LRU Cache", "lru-cache", "Medium", "LRU 缓存"),
    (148, "Sort List", "sort-list", "Medium", "排序链表"),
    (152, "Maximum Product Subarray", "maximum-product-subarray", "Medium", "乘积最大子数组"),
    (165, "Compare Version Numbers", "compare-version-numbers", "Medium", "比较版本号"),
    (198, "House Robber", "house-robber", "Medium", "打家劫舍"),
    (199, "Binary Tree Right Side View", "binary-tree-right-side-view", "Medium", "二叉树的右视图"),
    (200, "Number of Islands", "number-of-islands", "Medium", "岛屿数量"),
    (206, "Reverse Linked List", "reverse-linked-list", "Easy", "反转链表"),
    (207, "Course Schedule", "course-schedule", "Medium", "课程表"),
    (208, "Implement Trie (Prefix Tree)", "implement-trie-prefix-tree", "Medium", "实现 Trie"),
    (209, "Minimum Size Subarray Sum", "minimum-size-subarray-sum", "Medium", "长度最小的子数组"),
    (215, "Kth Largest Element in an Array", "kth-largest-element-in-an-array", "Medium", "数组中的第 K 个最大元素"),
    (226, "Invert Binary Tree", "invert-binary-tree", "Easy", "翻转二叉树"),
    (232, "Implement Queue using Stacks", "implement-queue-using-stacks", "Easy", "用栈实现队列"),
    (236, "Lowest Common Ancestor of a Binary Tree", "lowest-common-ancestor-of-a-binary-tree", "Medium", "二叉树的最近公共祖先"),
    (238, "Product of Array Except Self", "product-of-array-except-self", "Medium", "除自身以外数组的乘积"),
    (239, "Sliding Window Maximum", "sliding-window-maximum", "Hard", "滑动窗口最大值"),
    (279, "Perfect Squares", "perfect-squares", "Medium", "完全平方数"),
    (295, "Find Median from Data Stream", "find-median-from-data-stream", "Hard", "数据流的中位数"),
    (297, "Serialize and Deserialize Binary Tree", "serialize-and-deserialize-binary-tree", "Hard", "二叉树的序列化与反序列化"),
    (300, "Longest Increasing Subsequence", "longest-increasing-subsequence", "Medium", "最长递增子序列"),
    (322, "Coin Change", "coin-change", "Medium", "零钱兑换"),
    (347, "Top K Frequent Elements", "top-k-frequent-elements", "Medium", "前 K 个高频元素"),
    (415, "Add Strings", "add-strings", "Easy", "字符串相加"),
    (416, "Partition Equal Subset Sum", "partition-equal-subset-sum", "Medium", "分割等和子集"),
    (572, "Subtree of Another Tree", "subtree-of-another-tree", "Easy", "另一棵树的子树"),
    (695, "Max Area of Island", "max-area-of-island", "Medium", "岛屿的最大面积"),
    (902, "Numbers At Most N Given Digit Set", "numbers-at-most-n-given-digit-set", "Hard", "最大为 N 的数字组合"),
    (912, "Sort an Array", "sort-an-array", "Medium", "排序数组"),
    (1143, "Longest Common Subsequence", "longest-common-subsequence", "Medium", "最长公共子序列"),
]

_BY_ID: dict[int, dict[str, str]] = {}
_BY_TITLE_ZH: dict[str, int] = {}
_BY_TITLE_EN: dict[str, int] = {}


def _ensure_index() -> None:
    if _BY_ID:
        return
    for pid, title, slug, diff, title_zh in _LC_ENTRIES:
        _BY_ID[pid] = {
            "problem_id": str(pid),
            "title": title,
            "title_zh": title_zh,
            "slug": slug,
            "difficulty": diff,
        }
        _BY_TITLE_EN[title.lower()] = pid
        _BY_TITLE_ZH[title_zh] = pid


def lookup_by_id(problem_id: int) -> Optional[dict[str, Any]]:
    _ensure_index()
    row = _BY_ID.get(int(problem_id))
    if not row:
        return None
    return {
        "problem_id": int(problem_id),
        "title": row["title_zh"] or row["title"],
        "slug": row["slug"],
        "difficulty": row["difficulty"],
        "source": "lc_catalog",
    }


def lookup_by_title(text: str) -> Optional[dict[str, Any]]:
    _ensure_index()
    t = (text or "").strip()
    if not t:
        return None
    if t in _BY_TITLE_ZH:
        return lookup_by_id(_BY_TITLE_ZH[t])
    lower = t.lower()
    if lower in _BY_TITLE_EN:
        return lookup_by_id(_BY_TITLE_EN[lower])
    # 子串：中文标题
    for zh, pid in _BY_TITLE_ZH.items():
        if t in zh or zh in t:
            return lookup_by_id(pid)
    for en, pid in _BY_TITLE_EN.items():
        if lower in en or en in lower:
            return lookup_by_id(pid)
    return None


def catalog_size() -> int:
    _ensure_index()
    return len(_BY_ID)
