package com.codearena.business.learning.list.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "problem_list_items")
@IdClass(ProblemListItemEntity.Pk.class)
public class ProblemListItemEntity {

    @Id
    @Column(name = "list_id", length = 64)
    private String listId;

    @Id
    @Column(name = "problem_id")
    private Integer problemId;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 16)
    private String difficulty;

    @Column(name = "tags_json", nullable = false)
    private String tagsJson = "[]";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Getter
    @Setter
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String listId;
        private Integer problemId;
    }
}
