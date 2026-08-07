package com.codearena.business.learning.mastery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_problem_flags")
@IdClass(UserProblemFlagEntity.Pk.class)
public class UserProblemFlagEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "problem_id", nullable = false)
    private Integer problemId;

    @Column(nullable = false)
    private Boolean mastered = false;

    @Column(name = "mastered_at")
    private OffsetDateTime masteredAt;

    private String note;

    @Getter
    @Setter
    public static class Pk implements Serializable {
        private Long userId;
        private Integer problemId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(userId, pk.userId) && Objects.equals(problemId, pk.problemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, problemId);
        }
    }
}
