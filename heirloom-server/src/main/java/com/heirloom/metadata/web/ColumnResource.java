package com.heirloom.metadata.web;

import com.heirloom.core.metadata.ColumnDef;
import com.heirloom.metadata.domain.ColumnDefParser;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.TableRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/tables/{tableFQN}/columns")
public class ColumnResource {

    private final TableRepository tableRepo;

    public ColumnResource(TableRepository tableRepo) { this.tableRepo = tableRepo; }

    @GetMapping
    public List<ColumnDef> list(@PathVariable String tableFQN,
                                 @RequestParam(required = false) String tag) {
        TableEntity table = tableRepo.findByFQN(tableFQN)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found: " + tableFQN));
        List<ColumnDef> columns = ColumnDefParser.parse(table.getColumnsJson());
        if (tag != null && !tag.isEmpty()) {
            columns = columns.stream()
                .filter(c -> c.tags() != null && c.tags().contains(tag))
                .collect(Collectors.toList());
        }
        return columns;
    }
}
