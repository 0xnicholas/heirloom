package com.heirloom.pipeline.kafka;

public final class KafkaTopics {

    public static final String EVENTS = "heirloom.pipeline.events";
    public static final String DLQ = "heirloom.pipeline.dlq";

    public static final String GROUP_PROJECTOR = "heirloom-pipeline-projector";
    public static final String GROUP_STAGE_PREFIX = "heirloom-pipeline-stage-";

    public static String groupIdForStage(String stageName) {
        return GROUP_STAGE_PREFIX + stageName;
    }

    private KafkaTopics() {}
}
