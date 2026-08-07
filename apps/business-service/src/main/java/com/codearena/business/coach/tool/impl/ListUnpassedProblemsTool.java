package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.codearena.business.submission.domain.SubmissionEntity;
import com.codearena.business.submission.domain.SubmissionRepository;

@Component
@RequiredArgsConstructor
public class ListUnpassedProblemsTool implements CoachTool {
    private final SubmissionRepository submissionRepository;

    @Override
    public String name() {
        return "list_unpassed_problems";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "列出近期未通过（非 AC）的题目，用于续刷提议。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Map<Integer, SubmissionEntity> lastByProblem = new LinkedHashMap<>();
        submissionRepository.findAll().stream()
                .filter(s -> context.userId() == null
                        || context.userId().equals(s.getUserId())
                        || s.getUserId() == null)
                .sorted((a, b) -> {
                    if (a.getSubmittedAt() == null) {
                        return 1;
                    }
                    if (b.getSubmittedAt() == null) {
                        return -1;
                    }
                    return b.getSubmittedAt().compareTo(a.getSubmittedAt());
                })
                .forEach(s -> lastByProblem.putIfAbsent(s.getProblemId(), s));

        List<Map<String, Object>> items = new ArrayList<>();
        for (SubmissionEntity s : lastByProblem.values()) {
            if ("Accepted".equalsIgnoreCase(s.getStatus())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("problem_id", s.getProblemId());
            item.put("status", s.getStatus());
            items.add(item);
            if (items.size() >= 5) {
                break;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return CoachToolResult.success(data);
    }
}
