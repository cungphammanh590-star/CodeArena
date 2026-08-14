package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.knowledge.config.KnowledgeProperties;
import com.codearena.business.knowledge.service.KnowledgeService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class GetKnowledgePointTool implements CoachTool {
    private final KnowledgeService knowledgeService;
    private final KnowledgeProperties knowledgeProperties;

    @Override
    public String name() {
        return "get_knowledge_point";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "按 id 读取当前用户的知识点全文（用于引用用户笔记）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (!knowledgeProperties.isEnabled()) {
            return CoachToolResult.failure("knowledge base disabled");
        }
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        Integer kpId = context.paramInt("kp_id");
        if (kpId == null) {
            return CoachToolResult.failure("kp_id required");
        }
        try {
            Map<String, Object> kp = knowledgeService.getKp(context.userId(), kpId.longValue());
            return CoachToolResult.success(Map.of("knowledge_point", kp));
        } catch (ResponseStatusException e) {
            return CoachToolResult.failure(e.getReason() == null ? "not found" : e.getReason());
        }
    }
}
