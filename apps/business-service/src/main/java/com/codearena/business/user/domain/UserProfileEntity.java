package com.codearena.business.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(nullable = false, length = 16)
    private String locale = "zh-CN";

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
        if (locale == null) {
            locale = "zh-CN";
        }
        if (timezone == null) {
            timezone = "Asia/Shanghai";
        }
    }
}
