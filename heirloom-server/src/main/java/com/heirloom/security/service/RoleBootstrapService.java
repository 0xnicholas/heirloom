package com.heirloom.security.service;

import com.heirloom.repository.RoleRepository;
import com.heirloom.security.domain.Role;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RoleBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(RoleBootstrapService.class);
    private final RoleRepository roleRepo;

    public RoleBootstrapService(RoleRepository roleRepo) { this.roleRepo = roleRepo; }

    @PostConstruct
    void init() {
        createIfMissing("DataAnalyst", "ontology",
            "[{\"entityType\":\"*\",\"operation\":\"QUERY\"}]");
        createIfMissing("DataSteward", "ontology",
            "[{\"entityType\":\"*\",\"operation\":\"QUERY\"},{\"entityType\":\"resourceType\",\"operation\":\"MUTATE\"}]");
        createIfMissing("SupplyChainAnalyst", "ontology",
            "[{\"entityType\":\"resourceType\",\"operation\":\"QUERY\"},{\"entityType\":\"table\",\"operation\":\"QUERY\"}]");
    }

    private void createIfMissing(String name, String scope, String capabilitiesJson) {
        if (roleRepo.findByName(name).isEmpty()) {
            Role role = new Role();
            role.setName(name);
            role.setScope(scope);
            role.setCapabilities(capabilitiesJson);
            role.setDescription("Auto-created " + name + " role");
            roleRepo.create(role);
            log.info("Created default role: {}", name);
        }
    }
}
