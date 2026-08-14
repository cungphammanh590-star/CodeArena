package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.knowledge.config.KnowledgeProperties;
import com.codearena.business.knowledge.srs.KpSrsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetKpReviewDueTool implements CoachTool {

    private final KnowledgeProperties knowledgeProperties;
    private final KpSrsService kpSrsService;

    @Override
    public String name() {
        return "get_kp_review_due";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取用户知识点闪卡今日到期列表（八股/笔记复习），与刷题 get_review_due 不同。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (!knowledgeProperties.isEnabled()) {
            return CoachToolResult.failure("knowledge base disabled");
        }
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        Integer limit = context.paramInt("limit");
        return CoachToolResult.success(kpSrsService.dueToday(context.userId(), limit == null ? 20 : limit));
    }
}
