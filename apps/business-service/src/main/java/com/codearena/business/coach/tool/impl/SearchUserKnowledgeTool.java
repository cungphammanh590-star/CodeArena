package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.knowledge.config.KnowledgeProperties;
import com.codearena.business.knowledge.service.KnowledgeService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchUserKnowledgeTool implements CoachTool {
    private final KnowledgeService knowledgeService;
    private final KnowledgeProperties knowledgeProperties;

    @Override
    public String name() {
        return "search_user_knowledge";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "在用户私有知识库中语义检索知识点（八股笔记/学习材料），返回带来源的片段。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (!knowledgeProperties.isEnabled()) {
            return CoachToolResult.failure("knowledge base disabled");
        }
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        String query = context.paramString("query");
        if (query == null || query.isBlank()) {
            return CoachToolResult.failure("query required");
        }
        String topic = context.paramString("topic");
        int limit = 5;
        Integer lim = context.paramInt("limit");
        if (lim != null) {
            limit = lim;
        }
        Map<String, Object> data = knowledgeService.search(context.userId(), query, topic, limit);
        return CoachToolResult.success(data);
    }
}
