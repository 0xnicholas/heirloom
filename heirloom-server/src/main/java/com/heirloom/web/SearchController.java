package com.heirloom.web;

import com.heirloom.schema.domain.ResourceType;
import com.heirloom.repository.TypeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/search")
public class SearchController {

    private final TypeRepository typeRepo;

    public SearchController(TypeRepository typeRepo) {
        this.typeRepo = typeRepo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> search(@RequestParam String q) {
        String query = q.toLowerCase();

        // Search ResourceTypes by name and description
        List<Map<String, Object>> results = typeRepo.findAll().stream()
            .filter(t -> matches(t, query))
            .map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("entityType", "resourceType");
                m.put("name", t.getName());
                m.put("fqn", t.getFullyQualifiedName());
                m.put("description", t.getDescription());
                m.put("fields", t.getFields().stream().map(f -> f.name()).collect(Collectors.toList()));
                return m;
            })
            .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    private boolean matches(ResourceType type, String query) {
        if (type.getName() != null && type.getName().toLowerCase().contains(query)) return true;
        if (type.getDescription() != null && type.getDescription().toLowerCase().contains(query)) return true;
        return type.getFields().stream().anyMatch(f -> f.name().toLowerCase().contains(query));
    }
}
