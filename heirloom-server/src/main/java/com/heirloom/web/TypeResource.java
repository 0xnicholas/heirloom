package com.heirloom.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.dto.CreateTypeRequest;
import com.heirloom.schema.service.TypeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for ResourceType entities.
 * Replaces the old TypeController. Extends EntityResource for standard CRUD.
 */
@RestController
@RequestMapping("/v1/resourceTypes")
public class TypeResource extends EntityResource<ResourceType> {

    private final TypeRepository typeRepo;
    private final TypeService typeService;

    public TypeResource(Authorizer authorizer, TypeRepository typeRepo, TypeService typeService) {
        super(EntityRegistry.RESOURCE_TYPE, authorizer);
        this.typeRepo = typeRepo;
        this.typeService = typeService;
    }

    @GetMapping
    public ResponseEntity<List<ResourceType>> list() {
        return ResponseEntity.ok(typeRepo.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceType> getById(@PathVariable Long id) {
        return typeRepo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ResourceType> create(@RequestBody CreateTypeRequest request) {
        authorizer.authorize(Authorizer.Actor.anonymous(), entityType, "CREATE", null);
        ResourceType entity = typeService.buildEntity(request);
        typeService.validateCreate(entity);
        ResourceType saved = typeRepo.create(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        authorizer.authorize(Authorizer.Actor.anonymous(), entityType, "DELETE", null);
        typeRepo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
