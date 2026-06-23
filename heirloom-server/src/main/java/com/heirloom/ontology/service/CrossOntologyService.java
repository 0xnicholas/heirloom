package com.heirloom.ontology.service;

import com.heirloom.ontology.domain.Ontology;
import com.heirloom.ontology.domain.OntologyMapping;
import com.heirloom.ontology.repository.OntologyJpaRepository;
import com.heirloom.ontology.repository.OntologyMappingJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Phase 4.1 Cross-Ontology RID mapping service.
 *
 * <p>Foundation for federation: a registry of named ontologies plus a
 * directed mapping table between (ontology, RID) pairs. The service
 * answers two questions:
 * <ul>
 *   <li>Given a local (ontology, rid), what's its counterpart in another
 *       ontology? — {@link #resolve}.</li>
 *   <li>Given a local (ontology, rid), what are all known equivalents
 *       across the federation? — {@link #equivalents}.</li>
 * </ul>
 *
 * <p>The actual fan-out / federated execution of queries against multiple
 * ontologies is intentionally out of scope here — that would require
 * knowing the data sources for each ontology and a query planner. This
 * service is the registry + lookup half; the executor half is a follow-on.
 */
@Service
public class CrossOntologyService {

    private static final Logger log = LoggerFactory.getLogger(CrossOntologyService.class);
    private static final Set<String> VALID_TYPES = Set.of(
            OntologyMapping.TYPE_ALIAS,
            OntologyMapping.TYPE_EQUIVALENT,
            OntologyMapping.TYPE_RELATED,
            OntologyMapping.TYPE_DERIVED_FROM);

    private final OntologyJpaRepository ontologyRepo;
    private final OntologyMappingJpaRepository mappingRepo;

    public CrossOntologyService(OntologyJpaRepository ontologyRepo,
                                OntologyMappingJpaRepository mappingRepo) {
        this.ontologyRepo = ontologyRepo;
        this.mappingRepo = mappingRepo;
    }

    // === Ontology CRUD ===

    @Transactional
    public Ontology createOntology(String name, String description, String createdBy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ontology name must not be blank");
        }
        if (ontologyRepo.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Ontology already exists: " + name);
        }
        Ontology ontology = new Ontology();
        ontology.setName(name);
        ontology.setDescription(description);
        ontology.setCreatedAt(Instant.now());
        ontology.setCreatedBy(createdBy != null ? createdBy : "system");
        Ontology saved = ontologyRepo.save(ontology);
        log.info("Ontology '{}' created", name);
        return saved;
    }

    public List<Ontology> listOntologies() {
        return ontologyRepo.findAll();
    }

    public Optional<Ontology> getOntology(String name) {
        return ontologyRepo.findByName(name);
    }

    @Transactional
    public void deleteOntology(String name) {
        Ontology ontology = ontologyRepo.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Ontology not found: " + name));
        // Drop all mappings touching this ontology (any side).
        List<OntologyMapping> outgoing = mappingRepo.findBySourceOntology(name);
        List<OntologyMapping> incoming = mappingRepo.findByTargetOntology(name);
        for (OntologyMapping m : outgoing) mappingRepo.delete(m);
        for (OntologyMapping m : incoming) mappingRepo.delete(m);
        ontologyRepo.delete(ontology);
        log.info("Ontology '{}' deleted (with {} outgoing + {} incoming mappings)",
                name, outgoing.size(), incoming.size());
    }

    // === Mapping CRUD ===

    @Transactional
    public OntologyMapping registerMapping(String sourceOntology, String sourceRid,
                                           String targetOntology, String targetRid,
                                           String mappingType, double confidence,
                                           String createdBy, String notes) {
        if (!VALID_TYPES.contains(mappingType)) {
            throw new IllegalArgumentException(
                    "Unknown mapping_type: " + mappingType + " (valid: " + VALID_TYPES + ")");
        }
        if (Objects.equals(sourceOntology, targetOntology)
                && Objects.equals(sourceRid, targetRid)) {
            throw new IllegalArgumentException(
                    "Self-mapping is not allowed (source and target identical)");
        }
        requireOntology(sourceOntology);
        requireOntology(targetOntology);

        Optional<OntologyMapping> existing = mappingRepo
                .findBySourceOntologyAndSourceRidAndTargetOntologyAndMappingType(
                        sourceOntology, sourceRid, targetOntology, mappingType);
        if (existing.isPresent()) {
            // Idempotent re-registration — update confidence / notes in place.
            OntologyMapping m = existing.get();
            m.setConfidence(confidence);
            if (notes != null) m.setNotes(notes);
            return mappingRepo.save(m);
        }

        OntologyMapping mapping = new OntologyMapping();
        mapping.setSourceOntology(sourceOntology);
        mapping.setSourceRid(sourceRid);
        mapping.setTargetOntology(targetOntology);
        mapping.setTargetRid(targetRid);
        mapping.setMappingType(mappingType);
        mapping.setConfidence(confidence);
        mapping.setCreatedAt(Instant.now());
        mapping.setCreatedBy(createdBy != null ? createdBy : "system");
        mapping.setNotes(notes);
        OntologyMapping saved = mappingRepo.save(mapping);
        log.info("Mapping registered: {}:{} --[{}]--> {}:{} (conf={})",
                sourceOntology, sourceRid, mappingType,
                targetOntology, targetRid, saved.getConfidence());
        return saved;
    }

    public List<OntologyMapping> listMappings(String sourceOntology) {
        return mappingRepo.findBySourceOntology(sourceOntology);
    }

    @Transactional
    public void deleteMapping(Long id) {
        if (!mappingRepo.existsById(id)) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        mappingRepo.deleteById(id);
    }

    // === Resolution ===

    /**
     * Resolve a local (ontology, rid) to its counterpart in {@code targetOntology}.
     * If multiple mappings exist (different types), prefer the most specific:
     * ALIAS first, then EQUIVALENT, then RELATED, then DERIVED_FROM.
     */
    public Optional<ResolvedRid> resolve(String sourceOntology, String sourceRid,
                                        String targetOntology) {
        if (Objects.equals(sourceOntology, targetOntology)) {
            // Same ontology — pass-through.
            return Optional.of(new ResolvedRid(targetOntology, sourceRid,
                    OntologyMapping.TYPE_ALIAS, 1.0));
        }
        List<OntologyMapping> mappings =
                mappingRepo.findBySourceOntologyAndSourceRid(sourceOntology, sourceRid);
        return mappings.stream()
                .filter(m -> Objects.equals(m.getTargetOntology(), targetOntology))
                .min(Comparator.comparingDouble(this::rankingScore).reversed())
                .map(m -> new ResolvedRid(m.getTargetOntology(), m.getTargetRid(),
                        m.getMappingType(), m.getConfidence()));
    }

    /**
     * Find every known counterpart across all ontologies for a local
     * (ontology, rid). Includes incoming mappings too (someone else
     * pointing at us).
     */
    public List<ResolvedRid> equivalents(String ontology, String rid) {
        Map<String, ResolvedRid> best = new LinkedHashMap<>();
        // Outgoing edges — most specific first. Dedup by target ontology so
        // multiple mappings to the same ontology return the strongest one.
        for (OntologyMapping m : mappingRepo.findBySourceOntologyAndSourceRid(ontology, rid)) {
            best.merge(
                    m.getTargetOntology(),
                    new ResolvedRid(m.getTargetOntology(), m.getTargetRid(),
                            m.getMappingType(), m.getConfidence()),
                    this::preferBetter);
        }
        // Incoming edges — resolve backwards too.
        for (OntologyMapping m : mappingRepo.findByTargetOntologyAndTargetRid(ontology, rid)) {
            best.merge(
                    m.getSourceOntology(),
                    new ResolvedRid(m.getSourceOntology(), m.getSourceRid(),
                            reverse(m.getMappingType()), m.getConfidence()),
                    this::preferBetter);
        }
        return new ArrayList<>(best.values());
    }

    private static String reverse(String type) {
        return switch (type) {
            case OntologyMapping.TYPE_ALIAS -> OntologyMapping.TYPE_ALIAS;       // symmetric
            case OntologyMapping.TYPE_EQUIVALENT -> OntologyMapping.TYPE_EQUIVALENT;
            case OntologyMapping.TYPE_RELATED -> OntologyMapping.TYPE_RELATED;
            case OntologyMapping.TYPE_DERIVED_FROM -> "DERIVES"; // inverted
            default -> type;
        };
    }

    private double rankingScore(OntologyMapping m) {
        return switch (m.getMappingType()) {
            case OntologyMapping.TYPE_ALIAS -> 100 + m.getConfidence();
            case OntologyMapping.TYPE_EQUIVALENT -> 80 + m.getConfidence();
            case OntologyMapping.TYPE_RELATED -> 50 + m.getConfidence();
            case OntologyMapping.TYPE_DERIVED_FROM -> 30 + m.getConfidence();
            default -> m.getConfidence();
        };
    }

    private ResolvedRid preferBetter(ResolvedRid existing, ResolvedRid incoming) {
        return rankingFor(existing) >= rankingFor(incoming) ? existing : incoming;
    }

    private double rankingFor(ResolvedRid r) {
        return switch (r.mappingType()) {
            case OntologyMapping.TYPE_ALIAS -> 100 + r.confidence();
            case OntologyMapping.TYPE_EQUIVALENT -> 80 + r.confidence();
            case OntologyMapping.TYPE_RELATED -> 50 + r.confidence();
            case OntologyMapping.TYPE_DERIVED_FROM -> 30 + r.confidence();
            default -> r.confidence();
        };
    }

    private void requireOntology(String name) {
        if (ontologyRepo.findByName(name).isEmpty()) {
            throw new IllegalArgumentException("Unknown ontology: " + name);
        }
    }

    public record ResolvedRid(String ontology, String rid, String mappingType, double confidence) {}
}