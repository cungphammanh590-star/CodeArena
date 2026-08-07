package com.codearena.business.user.api;

import com.codearena.business.user.domain.UserEntity;

/**
 * 跨域只读门面：其他域应依赖本接口，而非 {@code UserService} 实现细节。
 *
 * <p>规划：拆 Maven 子模块 {@code user-api} 后，本接口与只读 DTO 迁入该 jar，
 * learning/coach 等仅依赖 api 模块，编译期看不见 service 实现。
 */
public interface UserLookup {

    UserEntity getByPublicId(String publicId);

    UserEntity getById(Long id);

    UserEntity ensureDefaultUser();
}
