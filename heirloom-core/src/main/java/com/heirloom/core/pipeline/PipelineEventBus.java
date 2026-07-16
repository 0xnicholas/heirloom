package com.heirloom.core.pipeline;

public interface PipelineEventBus {
    void publish(PipelineEvent event);

    /** Default no-op: 实现类可覆盖以支持 Kafka 等多订阅者总线。注册表模式（registry.register）由 StageRegistry 处理。 */
    default void subscribe(PipelineEventType type, PipelineStage subscriber) {}

    void start();
}