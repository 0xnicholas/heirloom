package com.heirloom.graph;

import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Neo4j native graph database implementation of GraphStore.
 * Active when {@code heirloom.graph.store-type} is {@code neo4j}.
 * Uses Neo4j Java Driver directly (no Spring Data overhead).
 *
 * Schema:
 * - (:Resource {rid}) nodes
 * - [r:RELATES {type, semantics, createdBy}] relationships
 */
@Component
@ConditionalOnProperty(name = "heirloom.graph.store-type", havingValue = "neo4j")
public class Neo4jGraphStore implements GraphStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphStore.class);

    private final Driver driver;
    private final boolean available;

    public Neo4jGraphStore() {
        String uri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
        String user = System.getenv().getOrDefault("NEO4J_USER", "neo4j");
        String password = System.getenv().getOrDefault("NEO4J_PASSWORD", "neo4j");

        Driver d = null;
        boolean ok = false;
        try {
            d = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
            d.verifyConnectivity();
            ok = true;
            log.info("Connected to Neo4j at {}", uri);
        } catch (Exception e) {
            log.warn("Neo4j not available at {}: {}. Falling back.", uri, e.getMessage());
        }
        this.driver = d;
        this.available = ok;
    }

    public boolean isAvailable() { return available; }

    @Override
    public void close() {
        if (driver != null) driver.close();
    }

    // ─── CRUD ───────────────────────────────────────────────────────────

    @Override
    public ResourceRelationshipEntity addRelationship(String sourceRid, String targetRid,
                                                       String relationshipType, String semantics,
                                                       String createdBy) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                // Create nodes if they don't exist, then create relationship
                tx.run("MERGE (s:Resource {rid: $sourceRid}) " +
                       "MERGE (t:Resource {rid: $targetRid}) " +
                       "MERGE (s)-[r:RELATES {type: $type}]->(t) " +
                       "SET r.semantics = $semantics, " +
                       "    r.createdBy = $createdBy, " +
                       "    r.createdAt = datetime() " +
                       "RETURN r",
                       Values.parameters(
                           "sourceRid", sourceRid,
                           "targetRid", targetRid,
                           "type", relationshipType,
                           "semantics", semantics,
                           "createdBy", createdBy != null ? createdBy : "system"
                       ));
                return null;
            });
        }
        log.info("Neo4j relationship: {} --[{}:{}]--> {}", sourceRid, semantics, relationshipType, targetRid);
        return null; // Neo4j doesn't use the same entity model
    }

    @Override
    public void removeRelationship(Long id) {
        // Neo4j uses RID pairs, not numeric IDs
        log.warn("removeRelationship(Long id) not supported by Neo4j backend");
    }

    @Override
    public void removeRelationship(String sourceRid, String targetRid, String relationshipType) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (s:Resource {rid: $sourceRid})" +
                       "-[r:RELATES {type: $type}]->" +
                       "(t:Resource {rid: $targetRid}) " +
                       "DELETE r",
                       Values.parameters(
                           "sourceRid", sourceRid,
                           "targetRid", targetRid,
                           "type", relationshipType
                       ));
                return null;
            });
        }
    }

    // ─── Cascade Delete ──────────────────────────────────────────────────

    @Override
    public Set<String> collectOwnedRids(String rid) {
        Set<String> result = new LinkedHashSet<>();
        try (var session = driver.session()) {
            var records = session.executeRead(tx ->
                tx.run("MATCH (s:Resource {rid: $rid})-[:RELATES {semantics: 'OWNERSHIP'}]->(owned) " +
                       "RETURN owned.rid AS rid",
                       Values.parameters("rid", rid)).list());
            for (var record : records) {
                result.add(record.get("rid").asString());
            }
        }
        return result;
    }

    @Override
    public List<String> breakReferences(String deletedRid) {
        List<String> sources = new ArrayList<>();
        try (var session = driver.session()) {
            var records = session.executeWrite(tx -> {
                var result = tx.run("MATCH (s:Resource)-[r:RELATES {semantics: 'REFERENCE'}]->" +
                                    "(t:Resource {rid: $deletedRid}) " +
                                    "DELETE r RETURN s.rid AS sourceRid",
                                    Values.parameters("deletedRid", deletedRid));
                return result.list();
            });
            for (var record : records) {
                sources.add(record.get("sourceRid").asString());
            }
        }
        return sources;
    }

    @Override
    public void removeAssociations(String deletedRid) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (r:Resource {rid: $deletedRid})-[rel:RELATES {semantics: 'ASSOCIATION'}]-( ) " +
                       "DELETE rel",
                       Values.parameters("deletedRid", deletedRid));
                return null;
            });
        }
    }

    // ─── Traversal ──────────────────────────────────────────────────────

    @Override
    public List<ResourceRelationshipEntity> traverseOutgoing(String rid) {
        List<ResourceRelationshipEntity> result = new ArrayList<>();
        try (var session = driver.session()) {
            var records = session.executeRead(tx ->
                tx.run("MATCH (s:Resource {rid: $rid})-[r:RELATES]->(t:Resource) " +
                       "RETURN t.rid AS targetRid, r.type AS type, r.semantics AS semantics " +
                       "ORDER BY r.semantics, r.type",
                       Values.parameters("rid", rid)).list());
            for (var record : records) {
                result.add(toEntity(rid, record));
            }
        }
        return result;
    }

    @Override
    public List<ResourceRelationshipEntity> traverseIncoming(String rid) {
        List<ResourceRelationshipEntity> result = new ArrayList<>();
        try (var session = driver.session()) {
            var records = session.executeRead(tx ->
                tx.run("MATCH (s:Resource)-[r:RELATES]->(t:Resource {rid: $rid}) " +
                       "RETURN s.rid AS sourceRid, r.type AS type, r.semantics AS semantics " +
                       "ORDER BY r.semantics, r.type",
                       Values.parameters("rid", rid)).list());
            for (var record : records) {
                result.add(toEntity(record.get("sourceRid").asString(), record));
            }
        }
        return result;
    }

    @Override
    public List<ResourceRelationshipEntity> traverseBfs(String startRid, int maxDepth) {
        List<ResourceRelationshipEntity> result = new ArrayList<>();
        try (var session = driver.session()) {
            var records = session.executeRead(tx ->
                tx.run("MATCH (s:Resource {rid: $startRid})" +
                       "-[r:RELATES*1..$maxDepth]->(t:Resource) " +
                       "RETURN s.rid AS sourceRid, t.rid AS targetRid, " +
                       "       r[0].type AS type, r[0].semantics AS semantics",
                       Values.parameters("startRid", startRid, "maxDepth", maxDepth)).list());
            for (var record : records) {
                result.add(toEntity(record.get("sourceRid").asString(), record));
            }
        }
        return result;
    }

    // ─── Permission Propagation ──────────────────────────────────────────

    @Override
    public String resolveUltimateOwner(String rid) {
        try (var session = driver.session()) {
            var result = session.executeRead(tx ->
                tx.run("MATCH (r:Resource {rid: $rid})" +
                       "OPTIONAL MATCH path = (owner)<-[:RELATES {semantics: 'OWNERSHIP'}]" +
                       "*-(r) " +
                       "RETURN owner.rid AS ultimateOwner " +
                       "ORDER BY length(path) DESC LIMIT 1",
                       Values.parameters("rid", rid)).single());
            if (result != null && result.get("ultimateOwner") != null) {
                return result.get("ultimateOwner").asString();
            }
        } catch (Exception e) {
            log.debug("No owner chain for {}: {}", rid, e.getMessage());
        }
        return rid;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private ResourceRelationshipEntity toEntity(String sourceRid, org.neo4j.driver.Record record) {
        var entity = new ResourceRelationshipEntity();
        entity.setSourceRid(sourceRid);
        entity.setTargetRid(record.get("targetRid").asString());
        entity.setRelationshipType(record.get("type").asString());
        entity.setSemantics(record.get("semantics").asString());
        return entity;
    }
}
