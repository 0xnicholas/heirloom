package com.heirloom.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class NoopAuthorizer implements Authorizer {

    @Override
    public void authorize(Actor actor, String entityType, String operation, String entityFQN) {
        // Phase 0: allow all operations
    }

    @Override
    public boolean isAdmin(Actor actor) {
        return true;
    }
}
