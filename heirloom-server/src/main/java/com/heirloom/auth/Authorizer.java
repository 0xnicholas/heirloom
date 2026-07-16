package com.heirloom.auth;

import com.heirloom.core.entity.HeirloomEntity;

/**
 * Pluggable authorization interface.
 * Phase 0: NoopAuthorizer (allow all).
 * Phase 2: RoleBasedAuthorizer (Role → Capability → Action model).
 */
public interface Authorizer {

    void authorize(Actor actor, String entityType, String operation, String entityFQN);

    boolean isAdmin(Actor actor);

    /** Minimal Actor representation — expands in Phase 2 */
    record Actor(String name, String type) {
        public static Actor anonymous() { return new Actor("anonymous", "system"); }
    }
}
