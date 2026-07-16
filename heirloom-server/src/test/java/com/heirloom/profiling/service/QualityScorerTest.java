package com.heirloom.profiling.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QualityScorerTest {
    @Test
    void shouldReturnHighScoreForCleanData() {
        double score = QualityScorer.score(0.0, 0.9, "varchar", null, 10.0);
        assertTrue(score > 0.7);
    }

    @Test
    void shouldPenalizeHighNullRate() {
        double highNull = QualityScorer.score(0.5, 0.5, "varchar", null, 10.0);
        double lowNull = QualityScorer.score(0.0, 0.5, "varchar", null, 10.0);
        assertTrue(lowNull > highNull);
    }

    @Test
    void shouldNeverExceedOne() {
        double score = QualityScorer.score(0.0, 1.0, "varchar", null, 10.0);
        assertTrue(score <= 1.0);
    }

    @Test
    void shouldNeverBeNegative() {
        double score = QualityScorer.score(1.0, 0.0, "varchar", null, null);
        assertTrue(score >= 0.0);
    }

    @Test
    void shouldReturnMediumScore() {
        double score = QualityScorer.score(0.3, 0.6, "varchar", null, 50.0);
        assertTrue(score >= 0.0 && score <= 1.0);
    }
}
