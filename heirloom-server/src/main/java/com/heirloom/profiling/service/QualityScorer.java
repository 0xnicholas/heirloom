package com.heirloom.profiling.service;

import com.heirloom.core.profiling.ValueFrequency;
import java.util.List;

public class QualityScorer {
    public static double score(double nullRate, double distinctRate, String dataType,
                                List<ValueFrequency> topValues, Double avgLength) {
        double score = 0;
        score += (1.0 - nullRate) * 0.30;
        score += distinctRate * 0.20;
        score += typeConsistency(dataType) * 0.20;
        score += enumStability(topValues) * 0.15;
        score += lengthConsistency(avgLength) * 0.15;
        return Math.min(1.0, Math.max(0.0, score));
    }

    private static double typeConsistency(String dataType) { return 1.0; }

    private static double enumStability(List<ValueFrequency> topValues) {
        if (topValues == null || topValues.size() < 5) return 1.0;
        double totalFreq = topValues.stream().mapToDouble(ValueFrequency::frequency).sum();
        return Math.min(1.0, totalFreq);
    }

    private static double lengthConsistency(Double avgLength) {
        if (avgLength == null || avgLength <= 0) return 1.0;
        return avgLength < 500 ? 1.0 : 0.5;
    }
}
