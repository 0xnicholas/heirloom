package com.heirloom.discovery.topology;

import com.heirloom.core.discovery.model.RawTable;
import com.heirloom.core.discovery.model.RawRelationship;
import com.heirloom.core.discovery.model.RawSchema;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DiscoveryContext {
    private final Map<String, Object> position = new ConcurrentHashMap<>();
    private final List<RawTable> tables = Collections.synchronizedList(new ArrayList<>());
    private final List<RawRelationship> relationships = Collections.synchronizedList(new ArrayList<>());
    private String sourceId, sourceType;

    public void setSource(String id, String type) { this.sourceId = id; this.sourceType = type; }
    public void put(String key, Object value) { position.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T get(String key) { return (T) position.get(key); }
    public void clear(String key) { position.remove(key); }
    public void addTable(RawTable t) { tables.add(t); }
    public void addRelationship(RawRelationship r) { relationships.add(r); }

    public DiscoveryContext copyForThread() {
        DiscoveryContext child = new DiscoveryContext();
        child.position.putAll(this.position);
        child.sourceId = this.sourceId;
        child.sourceType = this.sourceType;
        return child;
    }

    public RawSchema buildRawSchema() {
        StringBuilder sb = new StringBuilder();
        for (var t : tables) sb.append(t.tableName());
        String hash = Integer.toHexString(sb.toString().hashCode());
        return new RawSchema(sourceId, sourceType, List.copyOf(tables), List.copyOf(relationships), hash);
    }
}
