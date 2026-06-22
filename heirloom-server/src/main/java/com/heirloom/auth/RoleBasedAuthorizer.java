package com.heirloom.auth;

import com.heirloom.security.RoleCapabilityCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@Profile("prod")
public class RoleBasedAuthorizer implements Authorizer {
    private static final Logger log = LoggerFactory.getLogger(RoleBasedAuthorizer.class);

    private final RoleCapabilityCache capabilityCache;

    public RoleBasedAuthorizer(RoleCapabilityCache capabilityCache) {
        this.capabilityCache = capabilityCache;
    }

    @Override
    public void authorize(Actor actor, String entityType, String operation, String entityFQN) {
        if (isAdmin(actor)) return;

        // Phase 2: roles are looked up by actor name; in production, this would use
        // a proper Actor → Role mapping table
        List<Map<String, String>> capabilities = capabilityCache.get(actor.type());
        if (capabilities.isEmpty()) {
            log.warn("No capabilities resolved for actor type: {}", actor.type());
            throw new UnauthorizedException(entityType, operation, "No role assigned");
        }

        for (var cap : capabilities) {
            String capEntity = cap.get("entityType");
            String capOp = cap.get("operation");
            // Wildcard match: "*" entity or operation matches anything
            if (("*".equals(capEntity) || entityType.equals(capEntity))
                && ("*".equals(capOp) || operation.equals(capOp))) {
                return; // Authorized
            }
        }

        throw new UnauthorizedException(entityType, operation,
            "Actor " + actor.name() + " lacks capability: " + operation + " on " + entityType);
    }

    @Override
    public boolean isAdmin(Actor actor) {
        return "admin".equals(actor.type()) || "system".equals(actor.type());
    }
}