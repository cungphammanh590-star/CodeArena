package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.PlanGenerationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 按用户目标生成题单 ± 多日日程（company/topic/list/custom）。
 * 排期算法在 {@link PlanGenerationService}；自定义题单请先 preview 再 generate。
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
        return "生成刷题题单并排多日日程（会写库）。"
                + "用户贴题单：先 resolve_problem_refs → preview_study_plan，确认后再调用本工具，传 problem_ids。"
                + "goal_type=company|topic|list|custom；custom 必须带 problem_ids。"
                + "禁止自行编造长题号列表。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        var cmd = PreviewStudyPlanTool.toCommand(context);
        if ((cmd.goalType() == null || cmd.goalType().isBlank())
                && (cmd.problemIds() == null || cmd.problemIds().isEmpty())) {
            return CoachToolResult.failure("需要 goal_type+goal_ref，或 problem_ids");
        }
        if ((cmd.goalRef() == null || cmd.goalRef().isBlank())
                && (cmd.problemIds() == null || cmd.problemIds().isEmpty())) {
            return CoachToolResult.failure("需要 goal_ref 或 problem_ids");
        }
        Map<String, Object> result = planGenerationService.generate(cmd);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        if (!ok) {
            String note = result.get("note") == null ? "生成失败" : String.valueOf(result.get("note"));
            return new CoachToolResult(false, result, note);
        }
        return CoachToolResult.success(result);
    }
}
