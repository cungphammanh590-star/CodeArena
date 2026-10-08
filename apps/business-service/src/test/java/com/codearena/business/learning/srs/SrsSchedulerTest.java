package com.codearena.business.learning.srs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class SrsSchedulerTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-17T10:00:00+08:00");

    @Test
    void initialIntervalReflectsProblemDifficulty() {
        assertThat(SrsScheduler.enroll(now, "Hard").intervalDays()).isEqualTo(1);
        assertThat(SrsScheduler.enroll(now, "Medium").intervalDays()).isEqualTo(2);
        assertThat(SrsScheduler.enroll(now, "Easy").intervalDays()).isEqualTo(3);
    }

    @Test
    void manualGradesProduceDifferentSchedules() {
        SrsScheduler.Snapshot again = SrsScheduler.apply(2.5f, 6, 2, 0, SrsScheduler.Grade.AGAIN, now);
        SrsScheduler.Snapshot hard = SrsScheduler.apply(2.5f, 6, 2, 0, SrsScheduler.Grade.HARD, now);
        SrsScheduler.Snapshot good = SrsScheduler.apply(2.5f, 6, 2, 0, SrsScheduler.Grade.GOOD, now);
        SrsScheduler.Snapshot easy = SrsScheduler.apply(2.5f, 6, 2, 0, SrsScheduler.Grade.EASY, now);

        assertThat(again.intervalDays()).isLessThan(hard.intervalDays());
        assertThat(hard.intervalDays()).isLessThan(good.intervalDays());
        assertThat(good.intervalDays()).isLessThan(easy.intervalDays());
    }
}
