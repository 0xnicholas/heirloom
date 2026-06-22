package com.heirloom.schema.service;

import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.Field;
import com.heirloom.schema.domain.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerspectiveEngineTest {

    private final TypeRepository repo = mock(TypeRepository.class);
    private final PerspectiveEngine engine = new PerspectiveEngine(repo);

    private static ResourceType typeWith(String name, List<Field> fields,
                                         Map<String, List<String>> visibility) {
        ResourceType t = new ResourceType(name);
        t.setFields(fields);
        if (visibility != null) t.setFieldVisibility(visibility);
        return t;
    }

    private static Field field(String name) {
        return new Field(name, com.heirloom.schema.domain.FieldType.STRING, false);
    }

    @Test
    void noVisibilityConfig_returnsAllDeclaredFields() {
        when(repo.findByName("Customer"))
                .thenReturn(Optional.of(typeWith("Customer",
                        List.of(field("id"), field("name"), field("email")), null)));

        assertThat(engine.visibleFields("Customer", "any-role"))
                .containsExactly("id", "name", "email");
    }

    @Test
    void namedRoleCanSeeOnlyItsFields() {
        Map<String, List<String>> visibility = new HashMap<>();
        visibility.put("id", List.of("*"));
        visibility.put("name", List.of("*"));
        visibility.put("salary", List.of("HR"));
        visibility.put("ssn", List.of("HR", "Admin"));

        when(repo.findByName("Employee"))
                .thenReturn(Optional.of(typeWith("Employee",
                        List.of(field("id"), field("name"), field("salary"), field("ssn")),
                        visibility)));

        // HR sees salary and ssn
        assertThat(engine.visibleFields("Employee", "HR"))
                .containsExactly("id", "name", "salary", "ssn");
        // Admin sees ssn but not salary
        assertThat(engine.visibleFields("Employee", "Admin"))
                .containsExactly("id", "name", "ssn");
        // Random role sees only public fields
        assertThat(engine.visibleFields("Employee", "Random"))
                .containsExactly("id", "name");
    }

    @Test
    void wildcardInList_makesFieldVisibleToAll() {
        Map<String, List<String>> visibility = new HashMap<>();
        visibility.put("name", List.of("*"));
        visibility.put("salary", List.of("HR", "*")); // mixed wildcard + named

        when(repo.findByName("Employee"))
                .thenReturn(Optional.of(typeWith("Employee",
                        List.of(field("name"), field("salary")), visibility)));

        assertThat(engine.visibleFields("Employee", "anyone"))
                .containsExactlyInAnyOrder("name", "salary");
    }

    @Test
    void emptyVisibilityList_hidesField() {
        Map<String, List<String>> visibility = new HashMap<>();
        visibility.put("secret", List.of()); // explicit deny for everyone

        when(repo.findByName("Secret"))
                .thenReturn(Optional.of(typeWith("Secret",
                        List.of(field("name"), field("secret")), visibility)));

        assertThat(engine.visibleFields("Secret", "anyone")).containsExactly("name");
    }

    @Test
    void unknownType_returnsEmpty() {
        when(repo.findByName("ghost")).thenReturn(Optional.empty());
        assertThat(engine.visibleFields("ghost", "any")).isEmpty();
    }

    @Test
    void filterFields_stripsHiddenAndKeepsOrder() {
        Map<String, List<String>> visibility = new HashMap<>();
        visibility.put("ssn", List.of("Admin"));

        when(repo.findByName("Person"))
                .thenReturn(Optional.of(typeWith("Person",
                        List.of(field("id"), field("name"), field("ssn"), field("email")),
                        visibility)));

        List<String> result = engine.filterFields("Person", "BasicUser",
                List.of("id", "ssn", "name", "ssn", "email"));

        assertThat(result).containsExactly("id", "name", "email");
    }

    @Test
    void filterFields_emptyInput_returnsEmpty() {
        assertThat(engine.filterFields("Anything", "role", null)).isNull();
        assertThat(engine.filterFields("Anything", "role", List.of())).isEmpty();
    }

    @Test
    void cacheHit_secondCallDoesNotReQuery() {
        when(repo.findByName("Customer"))
                .thenReturn(Optional.of(typeWith("Customer",
                        List.of(field("id")), null)));

        engine.visibleFields("Customer", "role-A");
        engine.visibleFields("Customer", "role-A");
        engine.visibleFields("Customer", "role-A");

        // Repo queried exactly once for the (type, actor) pair.
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1)).findByName("Customer");
        assertThat(engine.cacheSize()).isEqualTo(1);
    }

    @Test
    void invalidateType_clearsOnlyMatchingEntries() {
        when(repo.findByName("Customer"))
                .thenReturn(Optional.of(typeWith("Customer", List.of(field("id")), null)));
        when(repo.findByName("Order"))
                .thenReturn(Optional.of(typeWith("Order", List.of(field("id")), null)));

        engine.visibleFields("Customer", "role-A");
        engine.visibleFields("Order", "role-A");
        assertThat(engine.cacheSize()).isEqualTo(2);

        engine.invalidateType("Customer");
        assertThat(engine.cacheSize()).isEqualTo(1);
    }

    @Test
    void invalidateAll_clearsEverything() {
        when(repo.findByName("Customer"))
                .thenReturn(Optional.of(typeWith("Customer", List.of(field("id")), null)));
        engine.visibleFields("Customer", "role-A");
        engine.visibleFields("Customer", "role-B");
        assertThat(engine.cacheSize()).isEqualTo(2);

        engine.invalidateAll();
        assertThat(engine.cacheSize()).isZero();
    }
}