package com.heirloom.schema.service;

import com.heirloom.repository.ResourceTypeJpaRepository;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.Field;
import com.heirloom.schema.domain.OntologyBranch;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.repository.OntologyBranchJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BranchServiceTest {

    private OntologyBranchJpaRepository branchRepo;
    private ResourceTypeJpaRepository typeJpa;
    private TypeRepository typeRepo;
    private BranchService service;
    private final List<ResourceType> typeStore = new ArrayList<>();

    @BeforeEach
    void setup() {
        branchRepo = mock(OntologyBranchJpaRepository.class);
        typeJpa = mock(ResourceTypeJpaRepository.class);
        typeRepo = mock(TypeRepository.class);
        service = new BranchService(branchRepo, typeJpa, typeRepo);
        typeStore.clear();
        when(typeJpa.findAll()).thenAnswer(inv -> new ArrayList<>(typeStore));
    }

    private static ResourceType mainType(String fqn, String hash) {
        ResourceType t = new ResourceType(fqn.substring(fqn.lastIndexOf('.') + 1));
        t.setFullyQualifiedName(fqn);
        t.setDomain("default");
        t.setChangeHash(hash);
        return t;
    }

    private static ResourceType branchClone(String fqn, String branchName, String hash) {
        ResourceType t = mainType(fqn, hash);
        t.setBranchName(branchName);
        return t;
    }

    private static OntologyBranch openBranch(String name, Map<String, String> baseHashes) throws Exception {
        OntologyBranch b = new OntologyBranch();
        b.setName(name);
        b.setStatus(OntologyBranch.STATUS_OPEN);
        b.setBaseHashes("{\"a\":\"1\"}");
        b.setTipHashes("{\"a\":\"1\"}");
        // Override via reflection for test simplicity
        var baseField = OntologyBranch.class.getDeclaredField("baseHashes");
        baseField.setAccessible(true);
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        baseField.set(b, m.writeValueAsString(baseHashes));
        return b;
    }

    @Test
    void createBranch_snapshotsMainHashes() throws Exception {
        typeStore.add(mainType("crm.Customer", "h-customer"));
        typeStore.add(mainType("crm.Order",    "h-order"));

        when(branchRepo.findByName("feature-x")).thenReturn(Optional.empty());
        when(branchRepo.save(any(OntologyBranch.class))).thenAnswer(inv -> inv.getArgument(0));

        OntologyBranch branch = service.createBranch("feature-x", "alice", "wip");

        assertThat(branch.getName()).isEqualTo("feature-x");
        assertThat(branch.getStatus()).isEqualTo(OntologyBranch.STATUS_OPEN);
        // The baseHashes JSON should contain both type hashes.
        assertThat(branch.getBaseHashes()).contains("crm.Customer").contains("h-customer");
        assertThat(branch.getBaseHashes()).contains("crm.Order").contains("h-order");
    }

    @Test
    void createBranch_duplicateName_throws() {
        when(branchRepo.findByName("dup")).thenReturn(Optional.of(new OntologyBranch()));

        assertThatThrownBy(() -> service.createBranch("dup", "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void previewMerge_classifiesAllFourOutcomes() throws Exception {
        // Base:   A=1,  B=2,  C=3,  D=4
        // Main:   A=1,  B=2', C=3,  D=4''  (B and D changed on main)
        // Branch: A=1', B=2,  C=3,  D=4'   (A and D changed on branch; D diverged)
        // Outcomes:
        //   A — branch-only
        //   B — main-only
        //   C — unchanged (no entry)
        //   D — conflict (main=4'' vs branch=4')
        typeStore.add(mainType("crm.A", "1"));
        typeStore.add(mainType("crm.B", "2'"));
        typeStore.add(mainType("crm.C", "3"));
        typeStore.add(mainType("crm.D", "4''"));
        typeStore.add(branchClone("crm.A", "feat", "1'"));
        typeStore.add(branchClone("crm.B", "feat", "2"));
        typeStore.add(branchClone("crm.D", "feat", "4'"));

        Map<String, String> base = Map.of(
                "crm.A", "1",
                "crm.B", "2",
                "crm.C", "3",
                "crm.D", "4");
        when(branchRepo.findByName("feat")).thenReturn(Optional.of(openBranch("feat", base)));

        BranchService.MergeReport report = service.previewMerge("feat");

        assertThat(report.conflicts())
                .extracting(BranchService.ConflictEntry::typeName)
                .containsExactly("crm.D");
        assertThat(report.safeBranchOnly()).containsExactly("crm.A");
        assertThat(report.safeMainOnly()).containsExactly("crm.B");
        // crm.C is unchanged on both sides — not in any list.
    }

    @Test
    void previewMerge_bothChangedToSame_isNotAConflict() throws Exception {
        typeStore.add(mainType("crm.X", "same"));
        typeStore.add(branchClone("crm.X", "feat", "same"));

        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.X", "old"))));

        BranchService.MergeReport report = service.previewMerge("feat");

        assertThat(report.conflicts()).isEmpty();
        assertThat(report.safeBranchOnly()).isEmpty();
        assertThat(report.safeMainOnly()).isEmpty();
    }

    @Test
    void previewMerge_typeAddedOnBranch_isBranchOnly() throws Exception {
        // Base has X; main still has X=old; branch added a new type Y.
        typeStore.add(mainType("crm.X", "old"));
        typeStore.add(branchClone("crm.X", "feat", "old"));
        typeStore.add(branchClone("crm.Y", "feat", "y-hash"));

        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.X", "old"))));

        BranchService.MergeReport report = service.previewMerge("feat");

        assertThat(report.safeBranchOnly()).contains("crm.Y");
    }

    @Test
    void previewMerge_typeDeletedOnMain_isMainOnly() throws Exception {
        // Base had X; main no longer has X; branch still has X unchanged from base.
        typeStore.add(branchClone("crm.X", "feat", "x-original"));

        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.X", "x-original"))));

        BranchService.MergeReport report = service.previewMerge("feat");

        assertThat(report.safeMainOnly()).contains("crm.X");
        assertThat(report.conflicts()).isEmpty();
    }

    @Test
    void applyMerge_requiresAllConflictsResolved() throws Exception {
        // Conflict: main changed to 4'', branch changed to 4'; both diverge from base 4.
        typeStore.add(mainType("crm.D", "4''"));
        typeStore.add(branchClone("crm.D", "feat", "4'"));
        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.D", "4"))));

        assertThatThrownBy(() -> service.applyMerge("feat", Map.of(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved conflicts");
    }

    @Test
    void applyMerge_promotesBranchOnlyAndMarksMerged() throws Exception {
        typeStore.add(mainType("crm.A", "1"));
        typeStore.add(branchClone("crm.A", "feat", "1'"));
        when(typeJpa.findByFullyQualifiedName("crm.A"))
                .thenReturn(Optional.of(mainType("crm.A", "1")));
        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.A", "1"))));
        when(branchRepo.save(any(OntologyBranch.class))).thenAnswer(inv -> inv.getArgument(0));

        BranchService.MergeResult result = service.applyMerge("feat", Map.of(), "alice");

        assertThat(result.promotedCount()).isEqualTo(1);
        // The branch clone was deleted (replaced by main update).
        verify(typeJpa).delete(any(ResourceType.class));
        // The branch itself is now MERGED.
        ArgumentCaptor<OntologyBranch> captor = ArgumentCaptor.forClass(OntologyBranch.class);
        verify(branchRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OntologyBranch.STATUS_MERGED);
        assertThat(captor.getValue().getMergedAt()).isNotNull();
        assertThat(captor.getValue().getMergedBy()).isEqualTo("alice");
    }

    @Test
    void applyMerge_takeBranch_resolution_promotesClone() throws Exception {
        // Conflict: main=4'', branch=4', base=4.
        typeStore.add(mainType("crm.D", "4''"));
        typeStore.add(branchClone("crm.D", "feat", "4'"));
        when(typeJpa.findByFullyQualifiedName("crm.D"))
                .thenReturn(Optional.of(mainType("crm.D", "4''")));
        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.D", "4"))));
        when(branchRepo.save(any(OntologyBranch.class))).thenAnswer(inv -> inv.getArgument(0));

        BranchService.MergeResult result = service.applyMerge(
                "feat", Map.of("crm.D", "branch"), "alice");

        assertThat(result.promotedCount()).isEqualTo(1);
        verify(typeJpa).save(any(ResourceType.class));
        verify(typeJpa).delete(any(ResourceType.class));
    }

    @Test
    void applyMerge_takeMain_resolution_deletesClone() throws Exception {
        // Conflict: main=4'', branch=4', base=4.
        typeStore.add(mainType("crm.D", "4''"));
        typeStore.add(branchClone("crm.D", "feat", "4'"));
        when(typeJpa.findByFullyQualifiedName("crm.D"))
                .thenReturn(Optional.of(mainType("crm.D", "4''")));
        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.D", "4"))));
        when(branchRepo.save(any(OntologyBranch.class))).thenAnswer(inv -> inv.getArgument(0));

        BranchService.MergeResult result = service.applyMerge(
                "feat", Map.of("crm.D", "main"), "alice");

        assertThat(result.keptCount()).isEqualTo(1);
        assertThat(result.promotedCount()).isZero();
        // "take main" just discards the branch clone — no main-row update needed.
        verify(typeJpa, times(1)).delete(any(ResourceType.class));
    }

    @Test
    void applyMerge_unknownResolution_throws() throws Exception {
        // Conflict case (different hashes on both sides).
        typeStore.add(mainType("crm.D", "4''"));
        typeStore.add(branchClone("crm.D", "feat", "4'"));
        when(branchRepo.findByName("feat")).thenReturn(
                Optional.of(openBranch("feat", Map.of("crm.D", "4"))));
        when(typeJpa.findByFullyQualifiedName("crm.D"))
                .thenReturn(Optional.of(mainType("crm.D", "4''")));

        assertThatThrownBy(() -> service.applyMerge(
                "feat", Map.of("crm.D", "garbage"), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("garbage");
    }

    @Test
    void applyMerge_unknownBranch_throws() {
        when(branchRepo.findByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyMerge("ghost", Map.of(), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void applyMerge_mergedBranch_throws() throws Exception {
        OntologyBranch merged = openBranch("feat", Map.of());
        merged.setStatus(OntologyBranch.STATUS_MERGED);
        when(branchRepo.findByName("feat")).thenReturn(Optional.of(merged));

        assertThatThrownBy(() -> service.applyMerge("feat", Map.of(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MERGED");
    }

    @Test
    void closeBranch_discardsClones() {
        typeStore.add(branchClone("crm.X", "feat", "x"));
        OntologyBranch open = new OntologyBranch();
        open.setName("feat");
        open.setStatus(OntologyBranch.STATUS_OPEN);
        when(branchRepo.findByName("feat")).thenReturn(Optional.of(open));

        service.closeBranch("feat");

        verify(typeJpa, atLeastOnce()).delete(any(ResourceType.class));
        ArgumentCaptor<OntologyBranch> captor = ArgumentCaptor.forClass(OntologyBranch.class);
        verify(branchRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OntologyBranch.STATUS_CLOSED);
    }

    @Test
    void closeBranch_alreadyMerged_throws() {
        OntologyBranch merged = new OntologyBranch();
        merged.setName("feat");
        merged.setStatus(OntologyBranch.STATUS_MERGED);
        when(branchRepo.findByName("feat")).thenReturn(Optional.of(merged));

        assertThatThrownBy(() -> service.closeBranch("feat"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("merged");
    }
}