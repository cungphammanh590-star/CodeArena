package com.codearena.business.coach.tool;

/**
 * 陪练工具策略：LangGraph 只决策 tool_name+params，真正读写在 Java。
 *
 * <p>新增工具：实现本接口并注册为 Spring Bean，无需改 Controller。
 */
public interface CoachTool {

    /** 与 LangGraph TOOL_SPECS 中 function.name 一致。 */
    String name();

    /** read：同步查询；write：改变会话/收藏等状态。 */
    Kind kind();

    String description();

    CoachToolResult execute(CoachToolContext context);

    enum Kind {
        READ,
        WRITE
    }
}
