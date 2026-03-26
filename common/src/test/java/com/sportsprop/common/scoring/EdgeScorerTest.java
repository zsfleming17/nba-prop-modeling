package com.sportsprop.common.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeScorerTest {

    @Test
    void probabilityOver_increasesWithMean() {
        double low = EdgeScorer.probabilityOver(10.0, 4.0, 24.5);
        double high = EdgeScorer.probabilityOver(28.0, 4.0, 24.5);
        assertTrue(high > low);
    }

    @Test
    void probabilityOver_bounded() {
        double p = EdgeScorer.probabilityOver(20.0, 3.0, 24.5);
        assertTrue(p >= 0.0 && p <= 1.0);
    }
}
