package com.heirloom.metadata.web;

import com.heirloom.metadata.domain.TagEntity;
import com.heirloom.metadata.repository.TagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/v1/tags")
public class TagResource {

    private final TagRepository repo;

    public TagResource(TagRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<TagEntity> list() {
        return repo.findAll();
    }

    @GetMapping("/{fqn}")
    public TagEntity get(@PathVariable String fqn) {
        return repo.findByFullyQualifiedName(fqn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    public TagEntity create(@RequestBody TagEntity entity) {
        entity.setFullyQualifiedName(entity.getName());
        return repo.create(entity);
    }

    @DeleteMapping("/{fqn}")
    public void delete(@PathVariable String fqn) {
        TagEntity entity = repo.findByFullyQualifiedName(fqn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(entity.getId());
    }
}
