package com.heirloom.graph;

import com.heirloom.graph.ResourceRelationshipEntity;

/**
 * DTO for resource relationships exposed over REST API.
 */
public record RelationshipDto(
    Long id, String sourceRid, String targetRid,
    String relationshipType, String semantics, String attributes,
    String createdBy
) {
    public static RelationshipDto from(ResourceRelationshipEntity e) {
        return new RelationshipDto(e.getId(), e.getSourceRid(), e.getTargetRid(),
            e.getRelationshipType(), e.getSemantics(), e.getAttributes(), e.getCreatedBy());
    }
}
