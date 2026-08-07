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
public class ForgetMemoryTool implements CoachTool {
    private final CoachMemoryService memoryService;

    @Override
    public String name() {
        return "forget_memory";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "软删除一条长期记忆（active=false）。需要 memory_id。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Long id = null;
        Object raw = context.param("memory_id");
        if (raw == null) {
            raw = context.param("id");
        }
        if (raw != null) {
            try {
                id = Long.valueOf(String.valueOf(raw));
            } catch (NumberFormatException ex) {
                return CoachToolResult.failure("memory_id invalid");
            }
        }
        if (id == null) {
            return CoachToolResult.failure("memory_id required");
        }
        UserCoachMemoryEntity row = memoryService.forget(context.userId(), id);
        Map<String, Object> data = new LinkedHashMap<>(memoryService.toView(row));
        data.put("note", "已软删除");
        return CoachToolResult.success(data);
    }
}
