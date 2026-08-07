package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.PlanGenerationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 按用户目标生成题单 ± 多日日程（公司/专题/题单等）。
 * 排期算法在 {@link PlanGenerationService}，本类只做参数适配。
 */
@Component
@RequiredArgsConstructor
public class GenerateStudyPlanTool implements CoachTool {

    private final PlanGenerationService planGenerationService;

    @Override
    public String name() {
        return "generate_study_plan";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "按用户目标生成刷题题单，并可排多日日程。"
                + " goal_type=company|topic|list；goal_ref 为公司名/专题名/题单id。"
                + " 说了天数则 schedule=true；只要题单可 schedule=false。"
                + " 禁止自行编造长题号列表。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        String goalType = context.paramString("goal_type");
        String goalRef = context.paramString("goal_ref");
        if (goalType == null || goalType.isBlank() || goalRef == null || goalRef.isBlank()) {
            return CoachToolResult.failure("需要 goal_type 与 goal_ref（如 company+Google 或 topic+动态规划）");
        }
        Boolean schedule = null;
        Object sch = context.param("schedule");
        if (sch instanceof Boolean b) {
            schedule = b;
        } else if (sch != null) {
            schedule = Boolean.parseBoolean(String.valueOf(sch));
        }
        // 有 days 默认排程
        Integer days = context.paramInt("days");
        if (schedule == null) {
            schedule = days != null && days > 0;
        }

        Map<String, Object> result = planGenerationService.generate(
                new PlanGenerationService.GenerateCommand(
                        context.userId(),
                        goalType,
                        goalRef,
                        context.paramString("title"),
                        days,
                        context.paramInt("daily_goal"),
                        schedule,
                        context.paramString("difficulty"),
                        context.paramInt("limit")));
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        if (!ok) {
            String note = result.get("note") == null ? "生成失败" : String.valueOf(result.get("note"));
            return new CoachToolResult(false, result, note);
        }
        return CoachToolResult.success(result);
    }
}
