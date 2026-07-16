package com.heirloom.query;

import com.heirloom.core.query.QueryParseException;
import com.heirloom.core.query.SemanticQuery;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("QueryParser")
@ExtendWith(MockitoExtension.class)
class QueryParserTest {

    @Mock
    private TypeRepository typeRepo;

    private QueryParser parser;
    private ResourceType customerType;

    @BeforeEach
    void setUp() {
        parser = new QueryParser(typeRepo);
        customerType = new ResourceType("Customer");
        customerType.setFields(List.of(
                new Field("name", FieldType.STRING, true),
                new Field("tier", FieldType.ENUM, false),
                new Field("arr", FieldType.NUMBER, false)
        ));
        customerType.setAbilities(List.of(Ability.KEY, Ability.QUERY));
    }

    @Test
    @DisplayName("parses valid query")
    void parsesValidQuery() {
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "Customer");
        request.put("fields", List.of("name", "tier"));
        request.put("limit", 50);

        SemanticQuery q = parser.parse(request);
        assertThat(q.getType()).isEqualTo("Customer");
        assertThat(q.getFields()).containsExactly("name", "tier");
        assertThat(q.getLimit()).isEqualTo(50);
    }

    @Test
    @DisplayName("rejects unknown type")
    void rejectsUnknownType() {
        when(typeRepo.findByName("Nonexistent")).thenReturn(Optional.empty());

        Map<String, Object> request = Map.of("type", "Nonexistent");
        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(QueryParseException.class)
                .hasMessageContaining("Nonexistent");
    }

    @Test
    @DisplayName("rejects undeclared field")
    void rejectsUndeclaredField() {
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "Customer");
        request.put("fields", List.of("ssn"));

        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(QueryParseException.class)
                .hasMessageContaining("ssn")
                .hasMessageContaining("not declared");
    }

    @Test
    @DisplayName("rejects SQL injection in field name")
    void rejectsSqlInjection() {
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "Customer");
        request.put("fields", List.of("1; DROP TABLE users"));

        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(QueryParseException.class)
                .hasMessageContaining("not declared");
    }

    @Test
    @DisplayName("rejects unknown filter operator")
    void rejectsUnknownOp() {
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "Customer");
        request.put("fields", List.of("name"));
        request.put("filter", Map.of("field", "name", "op", "$dangerous", "value", "x"));

        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(QueryParseException.class)
                .hasMessageContaining("$dangerous");
    }
}
