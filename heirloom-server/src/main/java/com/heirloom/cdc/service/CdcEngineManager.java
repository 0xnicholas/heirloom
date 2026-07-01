package com.heirloom.cdc.service;

import com.heirloom.cdc.domain.CdcSource;
import com.heirloom.cdc.repository.CdcOffsetRepository;
import com.heirloom.cdc.repository.CdcSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages CDC engine lifecycle — start, stop, status queries.
 */
@Component
public class CdcEngineManager {

    private static final Logger log = LoggerFactory.getLogger(CdcEngineManager.class);

    private final CdcSourceRepository sourceRepo;
    private final CdcOffsetRepository offsetRepo;
    private final CdcEventMapper eventMapper;
    private final Map<String, EngineState> engines = new ConcurrentHashMap<>();

    public CdcEngineManager(CdcSourceRepository sourceRepo, CdcOffsetRepository offsetRepo,
                            CdcEventMapper eventMapper) {
        this.sourceRepo = sourceRepo;
        this.offsetRepo = offsetRepo;
        this.eventMapper = eventMapper;
    }

    public void start(String sourceName) {
        if (engines.containsKey(sourceName)) {
            throw new IllegalStateException("CDC engine already running for: " + sourceName);
        }

        CdcSource source = sourceRepo.findByName(sourceName)
                .orElseThrow(() -> new IllegalArgumentException("CdcSource not found: " + sourceName));

        source.setStatus("STARTING");
        sourceRepo.save(source);

        CdcEngine engine = new CdcEngine(source, offsetRepo, eventMapper);
        Thread thread = new Thread(engine, "cdc-" + sourceName);
        thread.setDaemon(true);
        thread.start();

        engines.put(sourceName, new EngineState(engine, thread));

        source.setStatus("RUNNING");
        sourceRepo.save(source);
        log.info("CDC engine started for '{}'", sourceName);
    }

    public void stop(String sourceName) {
        EngineState state = engines.remove(sourceName);
        if (state == null) {
            throw new IllegalStateException("CDC engine not running for: " + sourceName);
        }
        state.engine().stop();
        try {
            state.thread().join(5000);
            if (state.thread().isAlive()) {
                log.warn("CDC engine thread did not stop within 5s for '{}', interrupting", sourceName);
                state.thread().interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        CdcSource source = sourceRepo.findByName(sourceName).orElse(null);
        if (source != null) {
            source.setStatus("STOPPED");
            sourceRepo.save(source);
        }
        log.info("CDC engine stopped for '{}'", sourceName);
    }

    public CdcStatus getStatus(String sourceName) {
        EngineState state = engines.get(sourceName);
        CdcSource source = sourceRepo.findByName(sourceName).orElse(null);

        if (source == null) return null;

        return new CdcStatus(
                source.getStatus(),
                state != null ? state.engine().getLastLsn() : null,
                state != null ? state.engine().getEventsProcessed() : 0
        );
    }

    public boolean isRunning(String sourceName) {
        return engines.containsKey(sourceName);
    }

    record EngineState(CdcEngine engine, Thread thread) {}

    public record CdcStatus(String status, String lastLsn, long eventsProcessed) {}
}
