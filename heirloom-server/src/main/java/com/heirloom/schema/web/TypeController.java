package com.heirloom.schema.web;

import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.dto.CreateTypeRequest;
import com.heirloom.schema.dto.TypeResponse;
import com.heirloom.schema.service.SchemaRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/types")
@Tag(name = "Schema Registry", description = "Resource Type definition and lifecycle")
public class TypeController {

    private final SchemaRegistryService service;

    public TypeController(SchemaRegistryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List all resource types")
    public List<TypeResponse> listTypes() {
        return service.listTypes().stream()
                .map(TypeResponse::from)
                .toList();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Get a resource type by name")
    public TypeResponse getType(@PathVariable String name) {
        return TypeResponse.from(service.getType(name));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new resource type")
    public TypeResponse createType(@Valid @RequestBody CreateTypeRequest request) {
        ResourceType created = service.createType(request);
        return TypeResponse.from(created);
    }

    @PutMapping("/{name}")
    @Operation(summary = "Update an existing resource type")
    public TypeResponse updateType(@PathVariable String name,
                                   @Valid @RequestBody CreateTypeRequest request) {
        ResourceType updated = service.updateType(name, request);
        return TypeResponse.from(updated);
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a resource type")
    public void deleteType(@PathVariable String name) {
        service.deleteType(name);
    }
}
