package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.code.domain.CoachCodeRunEntity;
import com.codearena.business.coach.code.domain.CoachCodeRunRepository;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 内部工具：只记录代码运行元数据，不保存源码、stdin 或输出。 */
@Component
@RequiredArgsConstructor
public class AppendCodeRunTool implements CoachTool {
    private final CoachCodeRunRepository repository;

    @Override
    public String name() {
        return "append_code_run";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "内部：追加沙箱代码运行审计元数据（不保存源码与输出）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.sessionId() == null || context.sessionId().isBlank()) {
            return CoachToolResult.failure("session_id required");
        }
        String language = context.paramString("language");
        if (language == null || language.isBlank()) {
            return CoachToolResult.failure("language required");
        }
        CoachCodeRunEntity row = new CoachCodeRunEntity();
        row.setUserId(context.userId());
        row.setSessionId(context.sessionId());
        row.setProblemId(context.problemId());
        row.setLanguage(trim(language, 16));
        row.setExitCode(context.paramInt("exit_code"));
        row.setTimedOut(Boolean.parseBoolean(context.paramString("timed_out")));
        row.setDurationMs(context.paramInt("duration_ms"));
        row.setSnippetHash(trim(context.paramString("snippet_hash"), 80));
        repository.save(row);
        return CoachToolResult.success(Map.of("code_run_id", row.getId(), "audited", true));
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
