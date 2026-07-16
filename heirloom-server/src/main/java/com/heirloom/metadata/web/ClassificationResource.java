package com.heirloom.metadata.web;

import com.heirloom.metadata.domain.ClassificationEntity;
import com.heirloom.metadata.repository.ClassificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/v1/classifications")
public class ClassificationResource {

    private final ClassificationRepository repo;

    public ClassificationResource(ClassificationRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ClassificationEntity> list() {
        return repo.findAll();
    }

    @GetMapping("/{fqn}")
    public ClassificationEntity get(@PathVariable String fqn) {
        return repo.findByFullyQualifiedName(fqn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    public ClassificationEntity create(@RequestBody ClassificationEntity entity) {
        entity.setFullyQualifiedName(entity.getName());
        return repo.create(entity);
    }

    @DeleteMapping("/{fqn}")
    public void delete(@PathVariable String fqn) {
        ClassificationEntity entity = repo.findByFullyQualifiedName(fqn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(entity.getId());
    }
}
