package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.srs.SrsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetReviewDueTool implements CoachTool {

    private final SrsService srsService;

    @Override
    public String name() {
        return "get_review_due";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "查询今日到期的间隔复习题（SRS）。与 get_today_tasks（计划排期）不同。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        Integer limit = context.paramInt("limit");
        return CoachToolResult.success(srsService.dueToday(context.userId(), limit == null ? 20 : limit));
    }
}
