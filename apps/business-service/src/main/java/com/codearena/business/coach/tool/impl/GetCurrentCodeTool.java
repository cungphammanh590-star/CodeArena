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
public class GetCurrentCodeTool implements CoachTool {
    private final SubmissionRepository submissionRepository;

    @Override
    public String name() {
        return "get_current_code";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取当前绑定题目最新提交的代码与状态（禁止历史 AC 源码泄露策略由执行层保证）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Integer pid = context.problemId();
        if (pid == null || pid <= 0) {
            return CoachToolResult.failure("尚未绑定题目");
        }
        List<SubmissionEntity> rows =
                submissionRepository.findTop80ByProblemIdOrderBySubmittedAtDesc(pid);
        SubmissionEntity latest = rows.stream()
                .filter(s -> context.userId() == null
                        || context.userId().equals(s.getUserId())
                        || s.getUserId() == null)
                .findFirst()
                .orElse(null);
        if (latest == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("code", "");
            empty.put("problem_id", pid);
            empty.put("note", "无提交记录");
            return CoachToolResult.success(empty);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("problem_id", pid);
        data.put("status", latest.getStatus());
        data.put("language", latest.getLanguage());
        data.put("code", latest.getCode() == null ? "" : latest.getCode());
        data.put("submission_id", latest.getSubmissionId());
        return CoachToolResult.success(data);
    }
}
