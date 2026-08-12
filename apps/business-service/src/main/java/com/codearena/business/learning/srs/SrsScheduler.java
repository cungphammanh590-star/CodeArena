package com.codearena.business.learning.srs;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * SM-2 风格调度（Anki 简化版）。
 *
 * <p>quality：again=1, hard=2, good=4, easy=5。
 */
public final class SrsScheduler {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public enum Grade {
        AGAIN(1, "again"),
        HARD(2, "hard"),
        GOOD(4, "good"),
        EASY(5, "easy");

        public final int quality;
        public final String label;

        Grade(int quality, String label) {
            this.quality = quality;
            this.label = label;
        }

        public static Grade fromLabel(String raw) {
            if (raw == null || raw.isBlank()) {
                return GOOD;
            }
            return switch (raw.trim().toLowerCase()) {
                case "again", "fail", "1" -> AGAIN;
                case "hard", "2" -> HARD;
                case "easy", "5" -> EASY;
                default -> GOOD;
            };
        }
    }

    public record Snapshot(
            float ease, int intervalDays, int reps, int lapses, OffsetDateTime dueAt, String outcome) {}

    private SrsScheduler() {}

    public static Snapshot apply(
            float ease, int intervalDays, int reps, int lapses, Grade grade, OffsetDateTime now) {
        float nextEase = ease <= 0 ? 2.5f : ease;
        int nextInterval = Math.max(0, intervalDays);
        int nextReps = Math.max(0, reps);
        int nextLapses = Math.max(0, lapses);

        if (grade.quality < 3) {
            nextLapses += 1;
            nextReps = 0;
            nextInterval = 1;
            nextEase = Math.max(1.3f, nextEase - 0.2f);
        } else {
            if (nextReps == 0) {
                nextInterval = 1;
            } else if (nextReps == 1) {
                nextInterval = grade == Grade.HARD ? 3 : (grade == Grade.EASY ? 4 : 6);
            } else {
                float factor = nextEase;
                if (grade == Grade.HARD) {
                    factor = Math.max(1.2f, factor * 0.85f);
                } else if (grade == Grade.EASY) {
                    factor = factor * 1.3f;
                }
                nextInterval = Math.max(1, Math.round(nextInterval * factor));
            }
            nextReps += 1;
            // SM-2 ease update
            nextEase = nextEase
                    + (0.1f
                            - (5 - grade.quality)
                                    * (0.08f + (5 - grade.quality) * 0.02f));
            if (nextEase < 1.3f) {
                nextEase = 1.3f;
            }
        }

        OffsetDateTime due = now.truncatedTo(ChronoUnit.SECONDS)
                .plusDays(nextInterval);
        return new Snapshot(nextEase, nextInterval, nextReps, nextLapses, due, grade.label);
    }

    /** 首次建卡：明天到期。 */
    public static Snapshot enroll(OffsetDateTime now) {
        return apply(2.5f, 0, 0, 0, Grade.GOOD, now);
    }
}
