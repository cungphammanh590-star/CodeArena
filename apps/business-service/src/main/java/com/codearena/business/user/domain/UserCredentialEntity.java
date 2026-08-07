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

/** 本地密码凭证预留表；鉴权未接入前 passwordHash 可为 null。 */
@Getter
@Setter
@Entity
@Table(name = "user_credentials")
public class UserCredentialEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
