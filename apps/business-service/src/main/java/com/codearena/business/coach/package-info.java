/**
 * 陪练编排域。
 *
 * <ul>
 *   <li>{@code web.external} — 对外 REST（{@code /api/coach/**}）</li>
 *   <li>{@code web.internal} — llm-service 回调（{@code /internal/tools/**}）</li>
 *   <li>{@code tool} / {@code tool.impl} — 策略接口与实现</li>
 *   <li>{@code service} — 可复用业务（逐步下沉）</li>
 * </ul>
 *
 * <p>流式对话在 llm-service；见 docs/architecture/COACH_TOOLS.md。
 */
package com.codearena.business.coach;
