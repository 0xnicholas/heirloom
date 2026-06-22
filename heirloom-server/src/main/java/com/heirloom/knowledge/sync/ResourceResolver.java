package com.heirloom.knowledge.sync;

import com.heirloom.knowledge.domain.EntityReference;

public class ResourceResolver {
    public EntityReference resolve(String resourceValue) {
        if (resourceValue == null || !resourceValue.startsWith("@")) return null;
        String fqn = resourceValue.substring(1);
        String entityType = inferEntityType(fqn);
        return new EntityReference(fqn, entityType, "Canonical resource", resourceValue);
    }

    String inferEntityType(String fqn) {
        if (fqn == null) return "unknown";
        if (fqn.startsWith("metadata_tables.")) return "table";
        if (fqn.startsWith("resourceType.")) return "resourceType";
        if (fqn.startsWith("knowledge.")) return "knowledgeArticle";
        int dot = fqn.indexOf('.');
        return dot > 0 ? fqn.substring(0, dot) : "unknown";
    }
}
