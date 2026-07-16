package com.heirloom.query;

import com.heirloom.core.query.AggregationQuery;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AggregationQueryTest {

    @Test
    void shouldParseSimpleCount() {
        var q = new AggregationQuery("Order",
            Map.of("$count", "*"),
            List.of("status"),
            Map.of());

        assertThat(q.type()).isEqualTo("Order");
        assertThat(q.aggregate()).containsEntry("$count", "*");
        assertThat(q.groupBy()).containsExactly("status");
    }

    @Test
    void shouldParseMultipleAggregates() {
        var q = new AggregationQuery("Order",
            Map.of("$count", "*", "$sum", "total", "$avg", "total"),
            List.of("status"),
            Map.of("field", "createdAt", "op", "$gte", "value", "2025-01-01"));

        assertThat(q.aggregate()).hasSize(3);
        assertThat(q.filter()).containsEntry("field", "createdAt");
    }
}
