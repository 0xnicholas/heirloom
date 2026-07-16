package com.heirloom.profiling.service;

import com.heirloom.core.profiling.DataClass;
import com.heirloom.core.profiling.ValueFrequency;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataClassInferrerTest {
    @Test
    void shouldDetectEnum() {
        var vals = List.of(new ValueFrequency("active", 800, 0.8), new ValueFrequency("inactive", 200, 0.2));
        var result = DataClassInferrer.infer("status", "varchar", 2, 0.02, 1000, vals, null, null, 7.0);
        assertEquals(DataClass.ENUM, result);
    }

    @Test
    void shouldDetectEmail() {
        var result = DataClassInferrer.infer("customer_email", "varchar", 500, 0.5, 1000, List.of(), null, null, 25.0);
        assertEquals(DataClass.EMAIL, result);
    }

    @Test
    void shouldDetectBoolean() {
        var vals = List.of(new ValueFrequency("true", 600, 0.6), new ValueFrequency("false", 400, 0.4));
        var result = DataClassInferrer.infer("is_active", "boolean", 2, 0.002, 1000, vals, null, null, null);
        assertEquals(DataClass.BOOLEAN_LIKE, result);
    }

    @Test
    void shouldDetectBooleanFromYesNo() {
        var vals = List.of(new ValueFrequency("yes", 700, 0.7), new ValueFrequency("no", 300, 0.3));
        var result = DataClassInferrer.infer("is_valid", "varchar", 2, 0.002, 1000, vals, null, null, 10.0);
        assertEquals(DataClass.BOOLEAN_LIKE, result);
    }

    @Test
    void shouldDetectPhone() {
        var result = DataClassInferrer.infer("user_phone", "varchar", 500, 0.5, 1000, List.of(), null, null, 12.0);
        assertEquals(DataClass.PHONE, result);
    }

    @Test
    void shouldReturnUnknownForNoMatch() {
        var result = DataClassInferrer.infer("description", "jsonb", 900, 0.9, 1000, List.of(), null, null, 200.0);
        assertEquals(DataClass.UNKNOWN, result);
    }

    @Test
    void shouldNotDetectBooleanWithMixedValues() {
        var vals = List.of(new ValueFrequency("true", 300, 0.3), new ValueFrequency("maybe", 700, 0.7));
        var result = DataClassInferrer.infer("flag", "varchar", 2, 0.002, 1000, vals, null, null, 10.0);
        assertNotEquals(DataClass.BOOLEAN_LIKE, result);
    }

    @Test
    void booleanDetectionCheckedFirst() {
        var vals = List.of(new ValueFrequency("true", 500, 0.5), new ValueFrequency("false", 500, 0.5));
        var result = DataClassInferrer.infer("user_email", "varchar", 2, 0.002, 1000, vals, null, null, 30.0);
        assertEquals(DataClass.BOOLEAN_LIKE, result);
    }
}
