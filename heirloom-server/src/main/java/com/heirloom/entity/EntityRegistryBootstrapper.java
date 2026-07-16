package com.heirloom.entity;

import com.heirloom.core.entity.EntityRegistry;
import org.springframework.stereotype.Component;

/**
 * Thin Spring wrapper that forces classloading of the core EntityRegistry
 * static utility at application startup. Repository beans then call
 * {@link EntityRegistry#register} in their {@code @PostConstruct} methods.
 */
@Component
public class EntityRegistryBootstrapper {

    static {
        EntityRegistry.getAllEntityTypes();
    }
}
