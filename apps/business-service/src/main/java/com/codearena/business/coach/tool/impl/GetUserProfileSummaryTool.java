package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.service.CoachMemoryService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetUserProfileSummaryTool implements CoachTool {
    private final SubmissionRepository submissionRepository;
    private final UserProblemFlagRepository flagRepository;
    private final CoachMemoryService memoryService;

    @Override
    public String name() {
        return "get_user_profile_summary";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取用户整体画像：提交量、掌握题数，以及活跃长期记忆摘要。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        long total = submissionRepository.findAll().stream()
                .filter(s -> context.userId() == null
                        || context.userId().equals(s.getUserId())
                        || s.getUserId() == null)
                .count();
        long mastered = flagRepository.findByUserIdAndMasteredTrue(context.userId()).size();
        List<Map<String, Object>> memories = memoryService.recall(context.userId(), null, 5).stream()
                .map(memoryService::toView)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_public_id", context.userPublicId());
        data.put("submission_count", total);
        data.put("mastered_count", mastered);
        data.put("memories", memories);
        data.put("note", "客观统计 + 活跃长期记忆摘要");
        return CoachToolResult.success(data);
    }
}
