package com.heirloom.schema.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.repository.ResourceTypeJpaRepository;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.OntologyBranch;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.repository.OntologyBranchJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Phase 4.1 Ontology Branching + merge conflict detection.
 *
 * <p>Git-like branching for {@link ResourceType} schemas:
 * <ul>
 *   <li>{@link #createBranch} snapshots the main state into a new branch.</li>
 *   <li>Branch edits go through the {@link TypeRepository} by setting
 *       {@code branchName} on the type; main queries ignore them.</li>
 *   <li>{@link #previewMerge} classifies every branch-local type as
 *       unchanged / branch-only / main-only / conflict.</li>
 *   <li>{@link #applyMerge} writes non-conflicting branch types back to
 *       main (and clears the {@code branchName} flag) after the caller has
 *       provided resolutions for any conflicts.</li>
 * </ul>
 *
 * <p>This MVP does NOT attempt three-way text merges — when both sides
 * have changed a type with diverging content, the caller chooses one.
 * That's the conservative behaviour; tooling on top can later do
 * structured merges of the JSON fields.
 */
@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> HASH_MAP = new TypeReference<>() {};

    private final OntologyBranchJpaRepository branchRepo;
    private final ResourceTypeJpaRepository typeJpa;
    private final TypeRepository typeRepo;

    public BranchService(OntologyBranchJpaRepository branchRepo,
                         ResourceTypeJpaRepository typeJpa,
                         TypeRepository typeRepo) {
        this.branchRepo = branchRepo;
        this.typeJpa = typeJpa;
        this.typeRepo = typeRepo;
    }

    // === Branch lifecycle ===

    @Transactional
    public OntologyBranch createBranch(String name, String createdBy, String description) {
        if (branchRepo.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Branch already exists: " + name);
        }
        Map<String, String> baseHashes = snapshotMainHashes();
        OntologyBranch branch = new OntologyBranch();
        branch.setName(name);
        branch.setBaseHashes(writeJson(baseHashes));
        branch.setTipHashes(writeJson(baseHashes));
        branch.setStatus(OntologyBranch.STATUS_OPEN);
        branch.setCreatedAt(Instant.now());
        branch.setCreatedBy(createdBy != null ? createdBy : "system");
        branch.setDescription(description);
        OntologyBranch saved = branchRepo.save(branch);
        log.info("Branch '{}' created with {} type hashes", name, baseHashes.size());
        return saved;
    }

    public Optional<OntologyBranch> getBranch(String name) {
        return branchRepo.findByName(name);
    }

    public List<OntologyBranch> listBranches() {
        return branchRepo.findAll();
    }

    @Transactional
    public void closeBranch(String name) {
        OntologyBranch branch = branchRepo.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + name));
        if (OntologyBranch.STATUS_MERGED.equals(branch.getStatus())) {
            throw new IllegalStateException("Cannot close a merged branch");
        }
        // Drop any branch-local clones (rollback to main).
        List<ResourceType> branchTypes = typeJpa.findAll().stream()
                .filter(t -> name.equals(t.getBranchName()))
                .toList();
        for (ResourceType t : branchTypes) typeJpa.delete(t);
        branch.setStatus(OntologyBranch.STATUS_CLOSED);
        branchRepo.save(branch);
    }

    // === Merge ===

    /**
     * Classify every branch-local type into one of:
     * unchanged / branch-only / main-only / conflict.
     */
    public MergeReport previewMerge(String name) {
        OntologyBranch branch = requireOpen(name);
        Map<String, String> base = readHashMap(branch.getBaseHashes());
        Map<String, String> mainNow = snapshotMainHashes();
        Map<String, String> branchNow = snapshotBranchHashes(name);

        List<ConflictEntry> conflicts = new ArrayList<>();
        List<String> safeBranchOnly = new ArrayList<>();
        List<String> safeMainOnly = new ArrayList<>();

        // Union of all types mentioned on either side.
        Set<String> allTypes = new TreeSet<>();
        allTypes.addAll(base.keySet());
        allTypes.addAll(mainNow.keySet());
        allTypes.addAll(branchNow.keySet());

        for (String typeName : allTypes) {
            String b = base.get(typeName);
            String m = mainNow.get(typeName);
            String br = branchNow.get(typeName);

            boolean mainChanged = !Objects.equals(b, m);
            boolean branchChanged = !Objects.equals(b, br);
            boolean sameOnBoth = Objects.equals(m, br);

            if (!mainChanged && !branchChanged) {
                // Unchanged — no action.
                continue;
            } else if (!mainChanged && branchChanged) {
                safeBranchOnly.add(typeName);
            } else if (mainChanged && !branchChanged) {
                safeMainOnly.add(typeName);
            } else if (mainChanged && branchChanged && sameOnBoth) {
                // Both changed to the same value — rare but possible.
                continue;
            } else {
                conflicts.add(new ConflictEntry(typeName, b, m, br));
            }
        }

        return new MergeReport(name, conflicts, safeBranchOnly, safeMainOnly);
    }

    /**
     * Apply the merge — promotes branch-local types back to main and clears
     * their {@code branchName}. {@code resolutions} maps each conflicted
     * type to {@code "main"} (keep main), {@code "branch"} (take branch),
     * or {@code "skip"} (leave both versions, mark branch as unmerged).
     */
    @Transactional
    public MergeResult applyMerge(String name, Map<String, String> resolutions, String mergedBy) {
        OntologyBranch branch = requireOpen(name);
        MergeReport preview = previewMerge(name);
        if (!preview.conflicts().isEmpty() && (resolutions == null
                || !resolutions.keySet().containsAll(
                        preview.conflicts().stream().map(ConflictEntry::typeName).toList()))) {
            throw new IllegalStateException(
                    "Unresolved conflicts: " + preview.conflicts().stream()
                            .map(ConflictEntry::typeName).toList());
        }

        int promoted = 0;
        int kept = 0;
        for (String typeName : preview.safeBranchOnly()) {
            promoteBranchToMain(typeName, name);
            promoted++;
        }
        for (ConflictEntry c : preview.conflicts()) {
            String decision = resolutions.get(c.typeName());
            switch (decision) {
                case "branch" -> {
                    promoteBranchToMain(c.typeName(), name);
                    promoted++;
                }
                case "main" -> {
                    deleteBranchClone(c.typeName(), name);
                    kept++;
                }
                case "skip" -> {
                    log.info("Skipping conflict resolution for type {}", c.typeName());
                }
                default -> throw new IllegalArgumentException(
                        "Unknown resolution '" + decision + "' for " + c.typeName());
            }
        }
        for (String typeName : preview.safeMainOnly()) {
            deleteBranchClone(typeName, name);
            kept++;
        }

        branch.setStatus(OntologyBranch.STATUS_MERGED);
        branch.setMergedAt(Instant.now());
        branch.setMergedBy(mergedBy != null ? mergedBy : "system");
        branchRepo.save(branch);

        log.info("Branch '{}' merged: {} promoted, {} kept (main wins)", name, promoted, kept);
        return new MergeResult(name, promoted, kept, preview.conflicts().size());
    }

    // === Helpers ===

    private OntologyBranch requireOpen(String name) {
        OntologyBranch branch = branchRepo.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + name));
        if (!OntologyBranch.STATUS_OPEN.equals(branch.getStatus())) {
            throw new IllegalStateException(
                    "Branch " + name + " is " + branch.getStatus() + " — cannot merge");
        }
        return branch;
    }

    private Map<String, String> snapshotMainHashes() {
        Map<String, String> result = new TreeMap<>();
        for (ResourceType t : typeJpa.findAll()) {
            if (t.getBranchName() == null) {
                result.put(t.getFullyQualifiedName(), hashOrEmpty(t));
            }
        }
        return result;
    }

    private Map<String, String> snapshotBranchHashes(String branchName) {
        // A branch inherits from main for any type it hasn't explicitly
        // overridden. So start with main's hashes and overlay branch clones.
        Map<String, String> result = new TreeMap<>(snapshotMainHashes());
        for (ResourceType t : typeJpa.findAll()) {
            if (branchName.equals(t.getBranchName())) {
                result.put(t.getFullyQualifiedName(), hashOrEmpty(t));
            }
        }
        return result;
    }

    private static String hashOrEmpty(ResourceType t) {
        return t.getChangeHash() != null ? t.getChangeHash() : "";
    }

    private void promoteBranchToMain(String typeFqn, String branchName) {
        ResourceType branchType = findBranchType(typeFqn, branchName);
        if (branchType == null) {
            log.warn("Branch type missing on promote: {}", typeFqn);
            return;
        }
        ResourceType main = typeJpa.findByFullyQualifiedName(typeFqn).orElse(null);
        if (main == null) {
            // No main row — drop the branchName and the row IS the main row.
            branchType.setBranchName(null);
            typeJpa.save(branchType);
        } else {
            // Copy fields from branch clone to main row, then delete the clone.
            main.setFields(branchType.getFields());
            main.setAbilities(branchType.getAbilities());
            main.setStateMachine(branchType.getStateMachine());
            main.setRelationships(branchType.getRelationships());
            main.setFieldVisibility(branchType.getFieldVisibility());
            main.setDescription(branchType.getDescription());
            main.setDomain(branchType.getDomain());
            // changeHash will be recomputed by TypeRepository.storeEntity.
            typeJpa.save(main);
            typeJpa.delete(branchType);
        }
    }

    private void deleteBranchClone(String typeFqn, String branchName) {
        ResourceType branchType = findBranchType(typeFqn, branchName);
        if (branchType != null) typeJpa.delete(branchType);
    }

    private ResourceType findBranchType(String typeFqn, String branchName) {
        return typeJpa.findAll().stream()
                .filter(t -> branchName.equals(t.getBranchName())
                        && typeFqn.equals(t.getFullyQualifiedName()))
                .findFirst()
                .orElse(null);
    }

    private static String writeJson(Map<String, String> map) {
        try { return MAPPER.writeValueAsString(map); }
        catch (Exception e) { throw new IllegalStateException("encode hashes", e); }
    }

    private static Map<String, String> readHashMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return MAPPER.readValue(json, HASH_MAP); }
        catch (Exception e) { throw new IllegalStateException("decode hashes", e); }
    }

    // === DTOs ===

    public record ConflictEntry(String typeName, String baseHash, String mainHash, String branchHash) {}

    public record MergeReport(String branchName,
                              List<ConflictEntry> conflicts,
                              List<String> safeBranchOnly,
                              List<String> safeMainOnly) {}

    public record MergeResult(String branchName, int promotedCount,
                              int keptCount, int conflictCount) {}
}