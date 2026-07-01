package com.heirloom.cdc.service;

import com.heirloom.cdc.domain.CdcSource;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Maps CDC events to ResourceService operations.
 * Handles RID generation, state column detection, and field mapping.
 */
@Component
public class CdcEventMapper {

    private static final Logger log = LoggerFactory.getLogger(CdcEventMapper.class);

    private final ResourceService resourceService;
    private final TypeRepository typeRepo;

    public CdcEventMapper(ResourceService resourceService, TypeRepository typeRepo) {
        this.resourceService = resourceService;
        this.typeRepo = typeRepo;
    }

    @Transactional
    public void handleEvent(CdcEvent event, CdcSource source) {
        CdcSource.TableConfig config = source.getWatchedTables().get(event.tableName());
        if (config == null) return;

        String resourceType = config.resourceType();

        switch (event.operation()) {
            case "INSERT" -> handleInsert(event, resourceType);
            case "UPDATE" -> handleUpdate(event, resourceType, config.stateColumn(), config.pkColumns());
            case "DELETE" -> handleDelete(event, resourceType, config.pkColumns());
        }
    }

    private void handleInsert(CdcEvent event, String resourceType) {
        String rid = buildDeterministicRid(resourceType, event.newValues(), List.of());
        Map<String, Object> fields = new LinkedHashMap<>(event.newValues());
        resourceService.createWithRid(rid, resourceType, "cdc-system", fields);
    }

    private void handleUpdate(CdcEvent event, String resourceType,
                               String explicitStateColumn, List<String> pkColumns) {
        String rid = buildDeterministicRid(resourceType, event.oldValues().isEmpty()
                ? event.newValues() : event.oldValues(), pkColumns);

        Map<String, Object> newFields = new LinkedHashMap<>(event.newValues());

        // Resolve state column: explicit config > auto-detect > null
        String stateColumn = resolveStateColumn(resourceType, explicitStateColumn);
        if (stateColumn != null && newFields.containsKey(stateColumn)) {
            String newState = Objects.toString(newFields.get(stateColumn), null);
            if (newState != null) {
                try {
                    resourceService.transitionState(rid, newState);
                } catch (Exception e) {
                    log.error("CDC state transition failed for rid={} to={}: {}", rid, newState, e.getMessage());
                }
            }
            newFields.remove(stateColumn); // handled separately
        }

        if (!newFields.isEmpty()) {
            resourceService.cdcUpdateFields(rid, newFields);
        }
    }

    private void handleDelete(CdcEvent event, String resourceType, List<String> pkColumns) {
        String rid = buildDeterministicRid(resourceType, event.oldValues(), pkColumns);
        try {
            resourceService.markDeleted(rid);
        } catch (Exception e) {
            log.error("CDC markDeleted failed for rid={}: {}", rid, e.getMessage());
        }
    }

    // --- RID generation ---

    String buildDeterministicRid(String resourceType, Map<String, String> values,
                                  List<String> pkColumns) {
        ResourceType type = typeRepo.findByName(resourceType).orElse(null);
        String domain = type != null ? type.getDomain() : "default";

        // If pkColumns are configured, only use those for the hash (stable RID across updates)
        // Otherwise fall back to all values (fine for INSERT, may break UPDATE with REPLICA IDENTITY FULL)
        Map<String, String> pkValues = new LinkedHashMap<>();
        if (pkColumns != null && !pkColumns.isEmpty()) {
            for (String pk : pkColumns) {
                if (values.containsKey(pk)) {
                    pkValues.put(pk, values.get(pk));
                }
            }
        }
        final Map<String, String> effectivePk = pkValues.isEmpty() ? values : pkValues;

        String pkConcat = effectivePk.keySet().stream().sorted()
                .map(k -> k + "=" + effectivePk.get(k))
                .reduce("", (a, b) -> a + "|" + b);

        String hash = sha256Hex(pkConcat).substring(0, 16);
        return domain + "." + resourceType + "." + hash;
    }

    // --- State column resolution ---

    private String resolveStateColumn(String resourceType, String explicitConfig) {
        if (explicitConfig != null && !explicitConfig.isBlank()) {
            return explicitConfig;
        }

        // Auto-detect: check if any field name matches a state machine state
        ResourceType type = typeRepo.findByName(resourceType).orElse(null);
        if (type == null || type.getStateMachine().isEmpty()) return null;

        Set<String> stateNames = new HashSet<>();
        type.getStateMachine().forEach(t -> {
            stateNames.add(t.from());
            stateNames.add(t.to());
        });

        for (var field : type.getFields()) {
            if (stateNames.contains(field.name())) {
                return field.name();
            }
        }
        return null;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
