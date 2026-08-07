package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.PlanQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetTodayTasksTool implements CoachTool {

    private final PlanQueryService planQueryService;

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
        return "查询当前用户今日刷题计划任务（基于 active study plan 与当天日期）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        return CoachToolResult.success(planQueryService.todayTasks(context.userId()));
    }
}
