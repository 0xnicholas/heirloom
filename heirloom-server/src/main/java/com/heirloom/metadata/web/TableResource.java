package com.heirloom.metadata.web;

import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.TableRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/v1/tables")
public class TableResource {

    private final TableRepository tableRepo;

    public TableResource(TableRepository tableRepo) { this.tableRepo = tableRepo; }

    @GetMapping
    public List<TableEntity> list() { return tableRepo.findAll(); }

    @GetMapping("/{fqn}")
    public TableEntity get(@PathVariable String fqn) {
        return tableRepo.findByFQN(fqn)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found: " + fqn));
    }
}
