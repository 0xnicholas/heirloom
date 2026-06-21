package com.heirloom.discovery.topology;

public record DiscoveryStage(String processor, String contextKey) {
    public static DiscoveryStage of(String processor, String contextKey) {
        return new DiscoveryStage(processor, contextKey);
    }
}
