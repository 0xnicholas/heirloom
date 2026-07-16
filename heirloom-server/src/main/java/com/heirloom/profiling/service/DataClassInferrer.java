package com.heirloom.profiling.service;

import com.heirloom.core.profiling.DataClass;
import com.heirloom.core.profiling.ValueFrequency;
import java.util.List;

public class DataClassInferrer {
    public static DataClass infer(String colName, String dataType,
                                   long distinctCount, double distinctRate,
                                   long total, List<ValueFrequency> topValues,
                                   String minValue, String maxValue, Double avgLength) {
        if (isBooleanLike(topValues)) return DataClass.BOOLEAN_LIKE;
        if (colName != null && colName.toLowerCase().contains("email") && avgLength != null && avgLength > 5)
            return DataClass.EMAIL;
        if (colName != null && (colName.toLowerCase().contains("phone") || colName.toLowerCase().contains("mobile")))
            return DataClass.PHONE;
        if (distinctRate < 0.05 && distinctCount > 0 && distinctCount <= 20)
            return DataClass.ENUM;
        if (isTemporal(minValue) || isTemporal(maxValue)) return DataClass.TEMPORAL;
        if (isNumeric(minValue) || isNumeric(maxValue)) return DataClass.NUMERIC;
        if (dataType != null && (dataType.contains("char") || dataType.contains("text")))
            return DataClass.TEXT;
        return DataClass.UNKNOWN;
    }

    private static boolean isBooleanLike(List<ValueFrequency> topValues) {
        if (topValues == null || topValues.isEmpty()) return false;
        return topValues.stream().allMatch(v ->
            "true".equalsIgnoreCase(v.value()) || "false".equalsIgnoreCase(v.value()) ||
            "0".equals(v.value()) || "1".equals(v.value()) || "yes".equalsIgnoreCase(v.value()) ||
            "no".equalsIgnoreCase(v.value()));
    }

    private static boolean isTemporal(String value) {
        if (value == null) return false;
        try { java.time.Instant.parse(value); return true; } catch (Exception e) {}
        try { java.time.LocalDate.parse(value); return true; } catch (Exception e) {}
        return false;
    }

    private static boolean isNumeric(String value) {
        if (value == null) return false;
        try { Double.parseDouble(value); return true; } catch (NumberFormatException e) { return false; }
    }
}
