package com.heirloom.web;

import com.heirloom.domain.Resource;
import com.heirloom.service.ResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for Resource instances.
 * <p>
 * Read endpoints are open. Write endpoints (POST/PUT/PATCH) are currently
 * unprotected — I-3 will introduce the Action pipeline as the authoritative
 * write path, after which these endpoints should be restricted.
 */
@RestController
@RequestMapping("/v1/resources")
public class ResourceResource {

    private final ResourceService resourceService;

    public ResourceResource(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String resourceType = (String) request.get("resourceType");
        String owner = (String) request.getOrDefault("owner", "system");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) request.getOrDefault("fields", Map.of());

        Resource created = resourceService.create(resourceType, owner, fields);
        return ResponseEntity.status(201).body(toResponse(created));
    }

    @GetMapping("/{rid}")
    public ResponseEntity<Map<String, Object>> getByRid(@PathVariable String rid) {
        Resource resource = resourceService.getByRid(rid);
        return ResponseEntity.ok(toResponse(resource));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        List<Resource> resources = resourceService.list(type, state, Map.of(), limit, offset);
        long total = resourceService.count(type, state);

        List<Map<String, Object>> items = resources.stream()
                .map(this::toResponse)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{rid}")
    public ResponseEntity<Map<String, Object>> updateFields(
            @PathVariable String rid,
            @RequestHeader(value = "If-Match", required = false) Long expectedVersion,
            @RequestBody Map<String, Object> request) {

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) request.getOrDefault("fields", Map.of());

        Resource updated = resourceService.updateFields(rid, fields,
                expectedVersion != null ? expectedVersion : 0L);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PatchMapping("/{rid}/state")
    public ResponseEntity<Map<String, Object>> transitionState(
            @PathVariable String rid,
            @RequestBody Map<String, Object> request) {

        String targetState = (String) request.get("targetState");
        if (targetState == null || targetState.isBlank()) {
            throw new IllegalArgumentException("targetState is required");
        }

        String previousState = resourceService.getByRid(rid).getCurrentState();
        Resource updated = resourceService.transitionState(rid, targetState);

        Map<String, Object> result = toResponse(updated);
        result.put("previousState", previousState);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toResponse(Resource r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rid", r.getRid());
        result.put("resourceType", r.getResourceType());
        result.put("owner", r.getOwner());
        result.put("currentState", r.getCurrentState());
        result.put("fields", r.getFields());
        result.put("version", r.getVersion());
        result.put("createdAt", r.getCreatedAt());
        result.put("updatedAt", r.getUpdatedAt());
        return result;
    }
}
