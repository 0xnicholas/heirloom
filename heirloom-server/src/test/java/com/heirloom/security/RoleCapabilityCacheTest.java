package com.heirloom.security;

import com.heirloom.repository.RoleRepository;
import com.heirloom.security.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RoleCapabilityCacheTest {

    private final RoleRepository repo = mock(RoleRepository.class);
    private final RoleCapabilityCache cache = new RoleCapabilityCache(repo);

    private static Role roleWith(String json) {
        Role r = new Role();
        r.setName("DataAnalyst");
        r.setCapabilities(json);
        return r;
    }

    @Test
    void cachesOnFirstAccess() {
        when(repo.findByName("DataAnalyst"))
                .thenReturn(Optional.of(roleWith(
                        "[{\"entityType\":\"*\",\"operation\":\"QUERY\"}]")));

        List<Map<String, String>> first = cache.get("DataAnalyst");
        List<Map<String, String>> second = cache.get("DataAnalyst");

        assertThat(first).hasSize(1);
        assertThat(second).isSameAs(first); // same instance — cached
        verify(repo, times(1)).findByName("DataAnalyst");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void invalidate_dropsSingleEntry() {
        when(repo.findByName("DataAnalyst"))
                .thenReturn(Optional.of(roleWith("[]")));

        cache.get("DataAnalyst");
        assertThat(cache.size()).isEqualTo(1);

        cache.invalidate("DataAnalyst");
        assertThat(cache.size()).isZero();

        cache.get("DataAnalyst");
        verify(repo, times(2)).findByName("DataAnalyst");
    }

    @Test
    void invalidateAll_clearsEverything() {
        when(repo.findByName("A")).thenReturn(Optional.of(roleWith("[]")));
        when(repo.findByName("B")).thenReturn(Optional.of(roleWith("[]")));

        cache.get("A");
        cache.get("B");
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidateAll();
        assertThat(cache.size()).isZero();
    }

    @Test
    void unknownRole_returnsEmptyList_andDoesNotPoisonCache() {
        when(repo.findByName("ghost")).thenReturn(Optional.empty());

        List<Map<String, String>> result = cache.get("ghost");

        assertThat(result).isEmpty();
        // Empty result still cached — repeat calls shouldn't re-hit the repo.
        cache.get("ghost");
        verify(repo, times(1)).findByName("ghost");
    }

    @Test
    void malformedCapabilitiesJson_returnsEmptyList() {
        when(repo.findByName("broken"))
                .thenReturn(Optional.of(roleWith("not-json{[")));

        assertThat(cache.get("broken")).isEmpty();
    }

    @Test
    void blankCapabilities_returnsEmptyList() {
        when(repo.findByName("blank")).thenReturn(Optional.of(roleWith("")));

        assertThat(cache.get("blank")).isEmpty();
    }

    @Test
    void nullRoleName_isTolerated() {
        assertThat(cache.get(null)).isEmpty();
        cache.invalidate(null); // no exception
    }
}