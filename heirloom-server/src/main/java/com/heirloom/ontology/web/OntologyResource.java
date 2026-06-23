package com.heirloom.ontology.web;

import com.heirloom.ontology.domain.Ontology;
import com.heirloom.ontology.domain.OntologyMapping;
import com.heirloom.ontology.service.CrossOntologyService;
import com.heirloom.ontology.service.CrossOntologyService.ResolvedRid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase 4.1 Cross-Ontology RID mapping — REST surface.
 *
 * <pre>
 * Ontology CRUD:
 *   POST   /v1/ontologies                  — create
 *   GET    /v1/ontologies                  — list
 *   GET    /v1/ontologies/{name}           — get one
 *   DELETE /v1/ontologies/{name}           — delete (cascades to mappings)
 *
 * Mapping CRUD:
 *   POST   /v1/ontologies/{name}/mappings  — register
 *   GET    /v1/ontologies/{name}/mappings  — list outgoing mappings
 *   DELETE /v1/ontologies/{name}/mappings/{id} — delete one
 *
 * Resolution:
 *   GET    /v1/ontologies/{name}/resolve?rid=X&target=Y — RID → target RID
 *   GET    /v1/ontologies/{name}/equivalents?rid=X     — all known equivalents
 * </pre>
 */
@RestController
@RequestMapping("/v1/ontologies")
public class OntologyResource {

    private final CrossOntologyService service;

    public OntologyResource(CrossOntologyService service) {
        this.service = service;
    }

    // === Ontology CRUD ===

    @PostMapping
    public ResponseEntity<Ontology> createOntology(@RequestBody CreateOntologyRequest body) {
        return ResponseEntity.ok(service.createOntology(
                body.name(), body.description(), body.createdBy()));
    }

    @GetMapping
    public ResponseEntity<List<Ontology>> listOntologies() {
        return ResponseEntity.ok(service.listOntologies());
    }

    @GetMapping("/{name}")
    public ResponseEntity<Ontology> getOntology(@PathVariable String name) {
        return service.getOntology(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deleteOntology(@PathVariable String name) {
        service.deleteOntology(name);
        return ResponseEntity.noContent().build();
    }

    // === Mapping CRUD ===

    @PostMapping("/{name}/mappings")
    public ResponseEntity<OntologyMapping> registerMapping(
            @PathVariable String name,
            @RequestBody RegisterMappingRequest body) {
        return ResponseEntity.ok(service.registerMapping(
                name,
                body.sourceRid(),
                body.targetOntology(),
                body.targetRid(),
                body.mappingType(),
                body.confidence(),
                body.createdBy(),
                body.notes()));
    }

    @GetMapping("/{name}/mappings")
    public ResponseEntity<List<OntologyMapping>> listMappings(@PathVariable String name) {
        return ResponseEntity.ok(service.listMappings(name));
    }

    @DeleteMapping("/{name}/mappings/{id}")
    public ResponseEntity<Void> deleteMapping(
            @PathVariable String name,
            @PathVariable Long id) {
        // Path 'name' is informational — the id is the source of truth.
        service.deleteMapping(id);
        return ResponseEntity.noContent().build();
    }

    // === Resolution ===

    @GetMapping("/{name}/resolve")
    public ResponseEntity<ResolvedRid> resolve(
            @PathVariable String name,
            @RequestParam("rid") String rid,
            @RequestParam("target") String targetOntology) {
        return service.resolve(name, rid, targetOntology)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{name}/equivalents")
    public ResponseEntity<List<ResolvedRid>> equivalents(
            @PathVariable String name,
            @RequestParam("rid") String rid) {
        return ResponseEntity.ok(service.equivalents(name, rid));
    }

    // === DTOs ===

    public record CreateOntologyRequest(String name, String description, String createdBy) {}

    public record RegisterMappingRequest(
            String sourceRid,
            String targetOntology,
            String targetRid,
            String mappingType,
            Double confidence,
            String createdBy,
            String notes) {}
}