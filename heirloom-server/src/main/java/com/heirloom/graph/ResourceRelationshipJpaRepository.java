package com.heirloom.graph;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResourceRelationshipJpaRepository
        extends JpaRepository<ResourceRelationshipEntity, Long> {

    List<ResourceRelationshipEntity> findBySourceRidAndDeletedFalse(String sourceRid);

    List<ResourceRelationshipEntity> findByTargetRidAndDeletedFalse(String targetRid);

    List<ResourceRelationshipEntity> findBySourceRidAndSemanticsAndDeletedFalse(
        String sourceRid, String semantics);

    List<ResourceRelationshipEntity> findByTargetRidAndSemanticsAndDeletedFalse(
        String targetRid, String semantics);

    Optional<ResourceRelationshipEntity> findBySourceRidAndTargetRidAndRelationshipTypeAndDeletedFalse(
        String sourceRid, String targetRid, String relationshipType);

    boolean existsBySourceRidAndTargetRidAndRelationshipTypeAndDeletedFalse(
        String sourceRid, String targetRid, String relationshipType);

    /** Recursive ownership chain: BFS-level query — finds all resources owned by a given RID.
     *  For deep chains, callers should iterate in service layer. */
    @Query("SELECT r FROM ResourceRelationshipEntity r " +
           "WHERE r.sourceRid = :rid AND r.semantics = 'OWNERSHIP' AND r.deleted = false")
    List<ResourceRelationshipEntity> findOwnedBy(@Param("rid") String rid);

    /** Find all outgoing edges for traversal. */
    @Query("SELECT r FROM ResourceRelationshipEntity r " +
           "WHERE r.sourceRid = :rid AND r.deleted = false " +
           "ORDER BY r.semantics, r.relationshipType")
    List<ResourceRelationshipEntity> findOutgoing(@Param("rid") String rid);

    /** Find all incoming edges for reverse traversal. */
    @Query("SELECT r FROM ResourceRelationshipEntity r " +
           "WHERE r.targetRid = :rid AND r.deleted = false " +
           "ORDER BY r.semantics, r.relationshipType")
    List<ResourceRelationshipEntity> findIncoming(@Param("rid") String rid);
}
