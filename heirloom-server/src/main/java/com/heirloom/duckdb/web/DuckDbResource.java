package com.heirloom.duckdb.web;

import com.heirloom.duckdb.DuckDbRawStore;
import com.heirloom.duckdb.DuckDbSyncService;
import com.heirloom.duckdb.FreshnessChecker;
import com.heirloom.duckdb.FreshnessStatus;
import com.heirloom.duckdb.SyncResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/v1/duckdb")
public class DuckDbResource {

    private final DuckDbSyncService syncService;
    private final FreshnessChecker freshnessChecker;
    private final DuckDbRawStore duckDb;

    public DuckDbResource(DuckDbSyncService syncService,
                          FreshnessChecker freshnessChecker,
                          DuckDbRawStore duckDb) {
        this.syncService = syncService;
        this.freshnessChecker = freshnessChecker;
        this.duckDb = duckDb;
    }

    @PostMapping("/sync/{tableFQN:.+}")
    public SyncResult sync(@PathVariable String tableFQN) {
        return syncService.sync(tableFQN);
    }

    @GetMapping("/tables/{tableFQN:.+}/freshness")
    public FreshnessStatus freshness(@PathVariable String tableFQN) {
        boolean fresh = freshnessChecker.isFresh(tableFQN);
        Instant last = freshnessChecker.lastSyncedAt(tableFQN);
        return new FreshnessStatus(tableFQN, fresh, last, freshnessChecker.ttlDescription());
    }

    @GetMapping("/tables")
    public List<String> tables() {
        var rows = duckDb.query(
            "SELECT table_name FROM information_schema.tables WHERE table_name LIKE '\\_raw\\_%' ESCAPE '\\'");
        List<String> names = new ArrayList<>();
        for (var row : rows) {
            Object name = row.get("table_name");
            if (name != null) names.add(name.toString());
        }
        return names;
    }
}