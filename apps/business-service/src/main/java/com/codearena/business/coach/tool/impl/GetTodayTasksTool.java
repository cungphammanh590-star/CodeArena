package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.PlanQueryService;
import com.codearena.business.learning.srs.SrsService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetTodayTasksTool implements CoachTool {

    private final PlanQueryService planQueryService;
    private final SrsService srsService;

    @Override
    public String name() {
        return "get_today_tasks";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "查询今日待办：计划排期（plan）+ 间隔复习到期（review）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        Map<String, Object> plan = planQueryService.todayTasks(context.userId());
        Map<String, Object> review = srsService.dueToday(context.userId(), 20);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("plan", plan);
        data.put("review", review);
        data.put("plan_count", plan.getOrDefault("count", 0));
        data.put("review_count", review.getOrDefault("count", 0));
        data.put("items", plan.get("items"));
        data.put("note", "plan=今日计划排期；review=SRS 到期复习。回答时请分开说明。");
        return CoachToolResult.success(data);
    }
}
