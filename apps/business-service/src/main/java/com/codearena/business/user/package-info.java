/**
 * 用户 / 账号域。
 *
 * <p>HTTP：{@code /api/users/**}（{@code web.external}）；内网 {@code /internal/users/**}（{@code web.internal}）。
 * 跨域请依赖 {@link com.codearena.business.user.api.UserLookup}，而非直接注入 {@code UserService}。
 */
package com.codearena.business.user;
