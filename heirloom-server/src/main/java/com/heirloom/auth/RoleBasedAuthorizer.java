package com.heirloom.auth;

import com.heirloom.repository.RoleRepository;
import com.heirloom.security.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@Profile("prod")
public class RoleBasedAuthorizer implements Authorizer {
    private static final Logger log = LoggerFactory.getLogger(RoleBasedAuthorizer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RoleRepository roleRepo;

    public RoleBasedAuthorizer(RoleRepository roleRepo) {
        this.roleRepo = roleRepo;
    }

    @Override
    public void authorize(Actor actor, String entityType, String operation, String entityFQN) {
        if (isAdmin(actor)) return;

        // Find roles for this actor
        // Phase 2: roles are looked up by actor name; in production, this would use
        // a proper Actor → Role mapping table
        var role = roleRepo.findByName(actor.type());
        if (role.isEmpty()) {
            log.warn("No role found for actor type: {}", actor.type());
            throw new UnauthorizedException(entityType, operation, "No role assigned");
        }

        // Parse capabilities JSON: [{"entityType":"resourceType","operation":"QUERY"},...]
        List<Map<String, String>> capabilities = parseCapabilities(role.get().getCapabilities());
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

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseCapabilities(String json) {
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse capabilities JSON: {}", json, e);
            return List.of();
        }
    }
}
