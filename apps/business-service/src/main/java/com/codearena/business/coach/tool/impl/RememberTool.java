package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.domain.UserCoachMemoryEntity;
import com.codearena.business.coach.memory.service.CoachMemoryService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RememberTool implements CoachTool {
    private final CoachMemoryService memoryService;

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "把值得跨会话保留的事实写入长期记忆（偏好/薄弱点/目标/笔记）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        String content = context.paramString("content");
        String kind = context.paramString("kind");
        String source = context.paramString("source");
        Integer problemId = context.paramInt("problem_id");
        if (problemId == null) {
            problemId = context.problemId();
        }
        Float confidence = null;
        Object c = context.param("confidence");
        if (c != null) {
            try {
                confidence = Float.valueOf(String.valueOf(c));
            } catch (NumberFormatException ignored) {
                confidence = null;
            }
        }
        UserCoachMemoryEntity row = memoryService.remember(
                context.userId(), kind, content, source, problemId, confidence);
        Map<String, Object> data = new LinkedHashMap<>(memoryService.toView(row));
        data.put("note", "已写入长期记忆");
        return CoachToolResult.success(data);
    }
}
