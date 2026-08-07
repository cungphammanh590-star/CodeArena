package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.PlanQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetActivePlanTool implements CoachTool {

    private final PlanQueryService planQueryService;

    @Override
    public String name() {
        return "get_active_plan";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "查询用户当前进行中的刷题计划摘要（goal、剩余天数、今日题量）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        return CoachToolResult.success(planQueryService.activePlanDigest(context.userId()));
    }
}
