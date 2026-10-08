/**
 * 用户 / 账号域。
 *
 * <p>HTTP：{@code /api/users/**}（{@code web.external}）；llm-service 内部调用经 gRPC。
 * 跨域请依赖 {@link com.codearena.business.user.api.UserLookup}，而非直接注入 {@code UserService}。
 */
package com.codearena.business.user;
