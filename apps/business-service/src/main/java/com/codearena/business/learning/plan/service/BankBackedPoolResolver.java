package com.codearena.business.learning.plan.service;

import com.codearena.business.learning.plan.domain.GoalProblemBankEntity;
import com.codearena.business.learning.plan.domain.GoalProblemBankRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BankBackedPoolResolver {

    private final GoalProblemBankRepository bankRepository;

    public List<PoolItem> fromBank(String goalType, String goalRef, String difficulty, int limit) {
        List<GoalProblemBankEntity> rows =
                bankRepository.findByGoalTypeAndGoalRefOrderBySortOrderAsc(goalType, goalRef);
        List<PoolItem> out = new ArrayList<>();
        for (GoalProblemBankEntity row : rows) {
            if (difficulty != null
                    && !difficulty.isBlank()
                    && !"mixed".equalsIgnoreCase(difficulty)
                    && row.getDifficulty() != null
                    && !row.getDifficulty().equalsIgnoreCase(difficulty.trim())) {
                continue;
            }
            out.add(new PoolItem(
                    row.getProblemId(),
                    row.getTitle(),
                    row.getSlug(),
                    row.getDifficulty(),
                    row.getStageHint(),
                    row.getSortOrder() == null ? 0 : row.getSortOrder()));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** Canonical goal_ref for known aliases. */
    public static String normalizeCompany(String raw) {
        String t = raw == null ? "" : raw.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("google", "Google");
        map.put("谷歌", "Google");
        map.put("meta", "Meta");
        map.put("facebook", "Meta");
        map.put("亚马逊", "Amazon");
        map.put("amazon", "Amazon");
        map.put("微软", "Microsoft");
        map.put("microsoft", "Microsoft");
        map.put("apple", "Apple");
        map.put("苹果", "Apple");
        return map.getOrDefault(lower, t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1));
    }

    public static String normalizeTopic(String raw) {
        String t = raw == null ? "" : raw.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("dp", "动态规划");
        map.put("动态规划", "动态规划");
        map.put("dynamic programming", "动态规划");
        map.put("链表", "链表");
        map.put("linked list", "链表");
        map.put("linkedlist", "链表");
        map.put("二叉树", "二叉树");
        map.put("树", "二叉树");
        map.put("binary tree", "二叉树");
        map.put("图", "图");
        map.put("graph", "图");
        map.put("二分", "二分查找");
        map.put("二分查找", "二分查找");
        map.put("binary search", "二分查找");
        return map.getOrDefault(lower, t);
    }
}
