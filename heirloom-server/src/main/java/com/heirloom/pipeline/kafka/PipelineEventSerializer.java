package com.heirloom.pipeline.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.heirloom.core.pipeline.PipelineEvent;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

public class PipelineEventSerializer implements Serializer<PipelineEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    public byte[] serialize(String topic, PipelineEvent event) {
        if (event == null) return null;
        try {
            return MAPPER.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize PipelineEvent", e);
        }
    }
}
