package com.heirloom.schema.service;

import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.dto.CreateTypeRequest;
import com.heirloom.schema.repository.ResourceTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class SchemaRegistryService {

    private final ResourceTypeRepository repository;

    public SchemaRegistryService(ResourceTypeRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ResourceType> listTypes() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public ResourceType getType(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new TypeNotFoundException(name));
    }

    public ResourceType createType(CreateTypeRequest request) {
        if (repository.existsByName(request.name())) {
            throw new TypeAlreadyExistsException(request.name());
        }

        ResourceType entity = request.toEntity();

        // Validate against existing types
        Map<String, ResourceType> knownTypes = buildKnownTypesMap(entity);
        List<TypeValidator.Diagnostic> diagnostics = TypeValidator.validate(entity, knownTypes);

        List<TypeValidator.Diagnostic> errors = diagnostics.stream()
                .filter(d -> d.severity() == TypeValidator.Severity.ERROR)
                .toList();

        if (!errors.isEmpty()) {
            throw new TypeValidationException(errors);
        }

        return repository.save(entity);
    }

    public ResourceType updateType(String name, CreateTypeRequest request) {
        ResourceType existing = getType(name);
        request.applyTo(existing);

        Map<String, ResourceType> knownTypes = buildKnownTypesMap(existing);
        List<TypeValidator.Diagnostic> diagnostics = TypeValidator.validate(existing, knownTypes);

        List<TypeValidator.Diagnostic> errors = diagnostics.stream()
                .filter(d -> d.severity() == TypeValidator.Severity.ERROR)
                .toList();

        if (!errors.isEmpty()) {
            throw new TypeValidationException(errors);
        }

        return repository.save(existing);
    }

    public void deleteType(String name) {
        ResourceType existing = getType(name);
        repository.delete(existing);
    }

    /**
     * Build a map of all known type names to their definitions,
     * excluding the type being validated (so it doesn't reference itself).
     */
    private Map<String, ResourceType> buildKnownTypesMap(ResourceType current) {
        return repository.findAll().stream()
                .filter(t -> !t.getName().equals(current.getName()))
                .collect(Collectors.toMap(ResourceType::getName, t -> t));
    }
}
