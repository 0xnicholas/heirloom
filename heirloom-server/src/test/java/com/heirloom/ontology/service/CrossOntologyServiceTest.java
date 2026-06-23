package com.heirloom.ontology.service;

import com.heirloom.ontology.domain.Ontology;
import com.heirloom.ontology.domain.OntologyMapping;
import com.heirloom.ontology.repository.OntologyJpaRepository;
import com.heirloom.ontology.repository.OntologyMappingJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CrossOntologyServiceTest {

    private OntologyJpaRepository ontologyRepo;
    private OntologyMappingJpaRepository mappingRepo;
    private CrossOntologyService service;
    private final List<Ontology> ontologyStore = new ArrayList<>();
    private final List<OntologyMapping> mappingStore = new ArrayList<>();

    @BeforeEach
    void setup() {
        ontologyRepo = mock(OntologyJpaRepository.class);
        mappingRepo = mock(OntologyMappingJpaRepository.class);
        service = new CrossOntologyService(ontologyRepo, mappingRepo);
        ontologyStore.clear();
        mappingStore.clear();

        when(ontologyRepo.findAll()).thenAnswer(inv -> new ArrayList<>(ontologyStore));
        when(ontologyRepo.findByName(any())).thenAnswer(inv ->
                ontologyStore.stream().filter(o -> o.getName().equals(inv.getArgument(0))).findFirst());
        when(ontologyRepo.save(any(Ontology.class))).thenAnswer(inv -> {
            Ontology o = inv.getArgument(0);
            if (!ontologyStore.contains(o)) ontologyStore.add(o);
            return o;
        });
        doAnswer(inv -> {
            Ontology o = inv.getArgument(0);
            ontologyStore.removeIf(x -> x.getName().equals(o.getName()));
            return null;
        }).when(ontologyRepo).delete(any(Ontology.class));
        doAnswer(inv -> {
            OntologyMapping m = inv.getArgument(0);
            mappingStore.removeIf(x ->
                    x.getSourceOntology().equals(m.getSourceOntology())
                    && x.getSourceRid().equals(m.getSourceRid())
                    && x.getTargetOntology().equals(m.getTargetOntology())
                    && x.getTargetRid().equals(m.getTargetRid())
                    && x.getMappingType().equals(m.getMappingType()));
            return null;
        }).when(mappingRepo).delete(any(OntologyMapping.class));

        when(mappingRepo.findBySourceOntologyAndSourceRid(any(), any())).thenAnswer(inv ->
                mappingStore.stream()
                        .filter(m -> m.getSourceOntology().equals(inv.getArgument(0))
                                && m.getSourceRid().equals(inv.getArgument(1)))
                        .toList());
        when(mappingRepo.findByTargetOntologyAndTargetRid(any(), any())).thenAnswer(inv ->
                mappingStore.stream()
                        .filter(m -> m.getTargetOntology().equals(inv.getArgument(0))
                                && m.getTargetRid().equals(inv.getArgument(1)))
                        .toList());
        when(mappingRepo.findBySourceOntology(any())).thenAnswer(inv ->
                mappingStore.stream()
                        .filter(m -> m.getSourceOntology().equals(inv.getArgument(0)))
                        .toList());
        when(mappingRepo.findByTargetOntology(any())).thenAnswer(inv ->
                mappingStore.stream()
                        .filter(m -> m.getTargetOntology().equals(inv.getArgument(0)))
                        .toList());
        when(mappingRepo.findBySourceOntologyAndSourceRidAndTargetOntologyAndMappingType(
                any(), any(), any(), any())).thenAnswer(inv -> mappingStore.stream()
                .filter(m -> m.getSourceOntology().equals(inv.getArgument(0))
                        && m.getSourceRid().equals(inv.getArgument(1))
                        && m.getTargetOntology().equals(inv.getArgument(2))
                        && m.getMappingType().equals(inv.getArgument(3)))
                .findFirst());
        when(mappingRepo.save(any(OntologyMapping.class))).thenAnswer(inv -> {
            OntologyMapping m = inv.getArgument(0);
            if (m.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(m, "id",
                        (long) (mappingStore.size() + 1));
            }
            if (!mappingStore.contains(m)) mappingStore.add(m);
            return m;
        });
    }

    private static Ontology ontology(String name) {
        Ontology o = new Ontology();
        o.setName(name);
        o.setDescription(name + " ontology");
        return o;
    }

    private static OntologyMapping mapping(String srcOnt, String srcRid,
                                          String tgtOnt, String tgtRid,
                                          String type, double confidence) {
        return mapping(srcOnt, srcRid, tgtOnt, tgtRid, type, confidence, null, null);
    }

    private static OntologyMapping mapping(String srcOnt, String srcRid,
                                          String tgtOnt, String tgtRid,
                                          String type, double confidence,
                                          String createdBy, String notes) {
        OntologyMapping m = new OntologyMapping();
        m.setSourceOntology(srcOnt);
        m.setSourceRid(srcRid);
        m.setTargetOntology(tgtOnt);
        m.setTargetRid(tgtRid);
        m.setMappingType(type);
        m.setConfidence(confidence);
        m.setCreatedBy(createdBy);
        m.setNotes(notes);
        return m;
    }

    @Test
    void createOntology_persists() {
        Ontology saved = service.createOntology("crm", "Customer domain", "alice");
        assertThat(saved.getName()).isEqualTo("crm");
        assertThat(ontologyStore).hasSize(1);
    }

    @Test
    void createOntology_duplicate_throws() {
        ontologyStore.add(ontology("crm"));
        assertThatThrownBy(() -> service.createOntology("crm", null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createOntology_blankName_throws() {
        assertThatThrownBy(() -> service.createOntology("", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteOntology_cascadesToMappings() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));
        mappingStore.add(mapping("crm", "x", "billing", "y",
                OntologyMapping.TYPE_ALIAS, 1.0));
        mappingStore.add(mapping("billing", "y", "crm", "x",
                OntologyMapping.TYPE_ALIAS, 1.0));

        service.deleteOntology("crm");

        // Service should have asked the mock to delete both mappings + the ontology.
        verify(mappingRepo, times(2)).delete(any(OntologyMapping.class));
        verify(ontologyRepo).delete(any(Ontology.class));

        // After delete callbacks fire, only 'billing' + empty mappings remain.
        assertThat(ontologyStore).extracting(Ontology::getName)
                .containsExactly("billing");
        assertThat(mappingStore).isEmpty();
    }

    @Test
    void registerMapping_validatesType() {
        ontologyStore.add(ontology("a"));
        ontologyStore.add(ontology("b"));
        assertThatThrownBy(() -> service.registerMapping(
                "a", "x", "b", "y", "GARBAGE", 1.0, "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown mapping_type");
    }

    @Test
    void registerMapping_rejectsSelfMapping() {
        ontologyStore.add(ontology("a"));
        assertThatThrownBy(() -> service.registerMapping(
                "a", "x", "a", "x", OntologyMapping.TYPE_ALIAS, 1.0, "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Self-mapping");
    }

    @Test
    void registerMapping_rejectsUnknownOntology() {
        ontologyStore.add(ontology("a"));
        assertThatThrownBy(() -> service.registerMapping(
                "a", "x", "ghost", "y", OntologyMapping.TYPE_ALIAS, 1.0, "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void registerMapping_persists() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));

        OntologyMapping saved = service.registerMapping(
                "crm", "customer/123", "billing", "acct/abc",
                OntologyMapping.TYPE_ALIAS, 0.95, "alice", "matched by email");

        assertThat(saved.getId()).isNotNull();
        assertThat(mappingStore).hasSize(1);
        assertThat(saved.getConfidence()).isEqualTo(0.95);
    }

    @Test
    void registerMapping_idempotentSameType() {
        ontologyStore.add(ontology("a"));
        ontologyStore.add(ontology("b"));

        service.registerMapping("a", "x", "b", "y",
                OntologyMapping.TYPE_ALIAS, 1.0, "alice", null);
        service.registerMapping("a", "x", "b", "y",
                OntologyMapping.TYPE_ALIAS, 0.8, "bob", "updated notes");

        assertThat(mappingStore).hasSize(1); // updated, not added
        OntologyMapping only = mappingStore.get(0);
        assertThat(only.getConfidence()).isEqualTo(0.8); // last write wins
        assertThat(only.getNotes()).isEqualTo("updated notes");
        assertThat(only.getCreatedBy()).isEqualTo("alice"); // original author preserved
    }

    @Test
    void registerMapping_differentTypesCoexist() {
        ontologyStore.add(ontology("a"));
        ontologyStore.add(ontology("b"));

        service.registerMapping("a", "x", "b", "y",
                OntologyMapping.TYPE_ALIAS, 1.0, "alice", null);
        service.registerMapping("a", "x", "b", "y",
                OntologyMapping.TYPE_RELATED, 0.5, "alice", null);

        assertThat(mappingStore).hasSize(2);
    }

    @Test
    void resolve_prefersAliasOverEquivalent() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));
        mappingStore.add(mapping("crm", "x", "billing", "y",
                OntologyMapping.TYPE_EQUIVALENT, 1.0, null, null));
        mappingStore.add(mapping("crm", "x", "billing", "y-strong",
                OntologyMapping.TYPE_ALIAS, 0.7, null, null));

        Optional<CrossOntologyService.ResolvedRid> result =
                service.resolve("crm", "x", "billing");

        assertThat(result).isPresent();
        assertThat(result.get().rid()).isEqualTo("y-strong");
        assertThat(result.get().mappingType()).isEqualTo(OntologyMapping.TYPE_ALIAS);
    }

    @Test
    void resolve_sameOntologyReturnsPassThrough() {
        Optional<CrossOntologyService.ResolvedRid> result =
                service.resolve("crm", "customer/123", "crm");
        assertThat(result).isPresent();
        assertThat(result.get().ontology()).isEqualTo("crm");
        assertThat(result.get().rid()).isEqualTo("customer/123");
    }

    @Test
    void resolve_unknownTargetReturnsEmpty() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));
        mappingStore.add(mapping("crm", "x", "billing", "y",
                OntologyMapping.TYPE_ALIAS, 1.0, null, null));

        Optional<CrossOntologyService.ResolvedRid> result =
                service.resolve("crm", "x", "ghost");
        assertThat(result).isEmpty();
    }

    @Test
    void equivalents_includesIncomingMappings() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));
        mappingStore.add(mapping("billing", "y", "crm", "x",
                OntologyMapping.TYPE_ALIAS, 1.0, null, null));

        List<CrossOntologyService.ResolvedRid> result =
                service.equivalents("crm", "x");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ontology()).isEqualTo("billing");
        assertThat(result.get(0).rid()).isEqualTo("y");
    }

    @Test
    void equivalents_dedupesByOntologyRid() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));
        mappingStore.add(mapping("crm", "x", "billing", "y",
                OntologyMapping.TYPE_ALIAS, 1.0, null, null));
        mappingStore.add(mapping("billing", "y", "crm", "x",
                OntologyMapping.TYPE_ALIAS, 0.9, null, null));

        List<CrossOntologyService.ResolvedRid> result =
                service.equivalents("crm", "x");

        assertThat(result).hasSize(1); // crm:x doesn't appear as equivalent of itself
        assertThat(result.get(0).ontology()).isEqualTo("billing");
        assertThat(result.get(0).rid()).isEqualTo("y");
    }

    @Test
    void equivalents_prefersStrongerMappingForSameTarget() {
        ontologyStore.add(ontology("crm"));
        ontologyStore.add(ontology("billing"));
        mappingStore.add(mapping("crm", "x", "billing", "y-equiv",
                OntologyMapping.TYPE_EQUIVALENT, 0.9, null, null));
        mappingStore.add(mapping("crm", "x", "billing", "y-alias",
                OntologyMapping.TYPE_ALIAS, 0.7, null, null));

        List<CrossOntologyService.ResolvedRid> result =
                service.equivalents("crm", "x");

        // Should pick the ALIAS one because ALIAS outranks EQUIVALENT.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).rid()).isEqualTo("y-alias");
    }

    @Test
    void deleteMapping_removesById() {
        ontologyStore.add(ontology("a"));
        ontologyStore.add(ontology("b"));
        OntologyMapping m = mapping("a", "x", "b", "y",
                OntologyMapping.TYPE_ALIAS, 1.0, null, null);
        mappingStore.add(m);
        when(mappingRepo.existsById(m.getId() != null ? m.getId() : 1L)).thenReturn(true);

        // Force the id since our save mock might not have applied.
        org.springframework.test.util.ReflectionTestUtils.setField(m, "id", 42L);
        when(mappingRepo.existsById(42L)).thenReturn(true);

        service.deleteMapping(42L);

        verify(mappingRepo).deleteById(42L);
    }

    @Test
    void deleteMapping_unknownId_throws() {
        when(mappingRepo.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteMapping(999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}