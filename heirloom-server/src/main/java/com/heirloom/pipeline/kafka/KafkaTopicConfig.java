package com.heirloom.pipeline.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${heirloom.pipeline.kafka.topic-events}")
    private String topicEvents;

    @Value("${heirloom.pipeline.kafka.topic-dlq}")
    private String topicDlq;

    @Value("${heirloom.pipeline.kafka.partitions:3}")
    private int partitions;

    @Value("${heirloom.pipeline.kafka.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public NewTopic eventsTopic() {
        return TopicBuilder.name(topicEvents)
            .partitions(partitions)
            .replicas(replicationFactor)
            .config("retention.ms", "604800000")  // 7 days
            .build();
    }

    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(topicDlq)
            .partitions(partitions)
            .replicas(replicationFactor)
            .config("retention.ms", "2592000000")  // 30 days
            .build();
    }
}
