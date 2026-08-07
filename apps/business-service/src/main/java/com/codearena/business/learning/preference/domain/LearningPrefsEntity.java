package com.codearena.business.learning.preference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "learning_prefs")
public class LearningPrefsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "list_mode", nullable = false)
    private Boolean listMode = true;

    @Column(name = "kg_mode", nullable = false)
    private Boolean kgMode = true;

    @Column(name = "active_list_id", nullable = false, length = 64)
    private String activeListId = "hot100";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
