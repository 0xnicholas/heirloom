package com.heirloom.schema.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.repository.ProposalJpaRepository;
import com.heirloom.schema.domain.Ability;
import com.heirloom.schema.domain.Proposal;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.dto.CreateTypeRequest;
import com.heirloom.service.EntityService;
import com.heirloom.repository.TypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business logic for ResourceType entities.
 * Was SchemaRegistryService — renamed and refactored to implement {@link EntityService}.
 * Structural validation moved to {@code TypeRepository.prepareInternal()}.
 */
@Service
public class TypeService implements EntityService<ResourceType> {

    private static final Logger log = LoggerFactory.getLogger(TypeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeRepository typeRepo;
    private final ProposalJpaRepository proposalJpa;

    public TypeService(TypeRepository typeRepo, ProposalJpaRepository proposalJpa) {
        this.typeRepo = typeRepo;
        this.proposalJpa = proposalJpa;
    }

    /**
     * Phase 2.1: Changing abilities requires a Proposal.
     * Creates a governance proposal instead of applying the change directly.
     * Returns the created Proposal, or null if abilities were not changed.
     */
    public Proposal proposeAbilityChange(String typeName, List<Ability> newAbilities, String proposedBy) {
        ResourceType type = typeRepo.findByName(typeName)
            .orElseThrow(() -> new IllegalArgumentException("Type not found: " + typeName));

        var oldAbilities = type.getAbilities();
        if (oldAbilities != null && oldAbilities.equals(newAbilities)) {
            log.info("No ability change for {} — identical", typeName);
            return null;
        }

        var proposal = new Proposal();
        proposal.setName("Change abilities on " + typeName);
        proposal.setTargetEntityType("resourceType");
        proposal.setTargetEntityFQN(type.getFullyQualifiedName());
        proposal.setChangeType("UPDATE_ABILITIES");
        proposal.setSource("governance");
        proposal.setProposedBy(proposedBy);
        proposal.setStatus("PENDING");

        try {
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("oldAbilities", oldAbilities);
            changes.put("newAbilities", newAbilities);
            proposal.setProposedChanges(MAPPER.writeValueAsString(changes));
        } catch (Exception e) {
            proposal.setProposedChanges("{\"error\":\"serialization failed\"}");
        }

        var saved = proposalJpa.save(proposal);
        log.info("Proposal created for ability change on {}: {} (proposal #{})",
            typeName, newAbilities, saved.getId());
        return saved;
    }

    @Override
    public ResourceType buildEntity(Object request) {
        if (request instanceof CreateTypeRequest req) {
            return req.toEntity();
        }
        throw new IllegalArgumentException("Expected CreateTypeRequest, got " + request.getClass());
    }

    @Override
    public void validateCreate(ResourceType entity) {
        if (typeRepo.existsByName(entity.getName())) {
            throw new TypeAlreadyExistsException(entity.getName());
        }
        if (entity.getName() != null && !entity.getName().isEmpty()
            && !Character.isUpperCase(entity.getName().charAt(0))) {
            throw new IllegalArgumentException("Type name must be PascalCase");
        }
    }

    @Override
    public void validateUpdate(ResourceType existing, ResourceType incoming) {
        // Business validation for updates
    }

    @Override
    public void validateDelete(ResourceType entity) {
        // Business validation for deletes (e.g., check no active instances)
    }

    @Override
    public Map<String, Object> toResponse(ResourceType entity, Set<String> fields) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("name", entity.getName());
        result.put("fullyQualifiedName", entity.getFullyQualifiedName());
        result.put("description", entity.getDescription() != null ? entity.getDescription() : "");
        result.put("domain", entity.getDomain());
        result.put("fields", entity.getFields());
        result.put("abilities", entity.getAbilities());
        result.put("stateMachine", entity.getStateMachine());
        result.put("relationships", entity.getRelationships());
        result.put("version", entity.getVersion());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        return result;
    }
}
