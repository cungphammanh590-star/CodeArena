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
public class GetLatestSubmissionTool implements CoachTool {
    private final SubmissionRepository submissionRepository;

    @Override
    public String name() {
        return "get_latest_submission";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取库内该用户最近一条提交的题号/状态（不含源码）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        List<SubmissionEntity> all = submissionRepository.findAll();
        SubmissionEntity latest = all.stream()
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
                .findFirst()
                .orElse(null);
        if (latest == null) {
            return CoachToolResult.failure("无提交");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem_id", latest.getProblemId());
        data.put("status", latest.getStatus());
        data.put("language", latest.getLanguage());
        data.put("submission_id", latest.getSubmissionId());
        data.put(
                "submitted_at",
                latest.getSubmittedAt() == null ? null : latest.getSubmittedAt().toString());
        // 故意不返回 code
        return CoachToolResult.success(data);
    }
}
