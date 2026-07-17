package com.heirloom.pipeline.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.heirloom.core.pipeline.*;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

public class PipelineEventDeserializer implements Deserializer<PipelineEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public PipelineEvent deserialize(String topic, byte[] data) {
        if (data == null) return null;
        try {
            JsonNode root = MAPPER.readTree(data);
            String type = root.get("type").asText();
            return switch (PipelineEventType.valueOf(type)) {
                case INGESTION_REQUESTED -> MAPPER.treeToValue(root, IngestionRequested.class);
                case RAW_DATA_INGESTED -> MAPPER.treeToValue(root, RawDataIngested.class);
                case SCHEMA_DISCOVERED -> MAPPER.treeToValue(root, SchemaDiscovered.class);
                case DATA_PROFILED -> MAPPER.treeToValue(root, DataProfiled.class);
                case SEMANTIC_ALIGNED -> MAPPER.treeToValue(root, SemanticAligned.class);
                case ENTITIES_RESOLVED -> MAPPER.treeToValue(root, EntitiesResolved.class);
                case ONTOLOGY_PROPOSED -> MAPPER.treeToValue(root, OntologyProposed.class);
                case PROPOSAL_APPROVED -> MAPPER.treeToValue(root, ProposalApproved.class);
                case PROPOSAL_REJECTED -> MAPPER.treeToValue(root, ProposalRejected.class);
                case ONTOLOGY_PUBLISHED -> MAPPER.treeToValue(root, OntologyPublished.class);
            };
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize PipelineEvent", e);
        }
    }
}
