package com.heirloom.discovery.topology;

import java.util.List;

public record DiscoveryNode(String name, String producer, List<DiscoveryStage> stages,
                             List<String> children, List<String> postProcess, boolean threads) {

    public static Builder builder(String name) { return new Builder(name); }

    public static class Builder {
        private final String name;
        private String producer;
        private List<DiscoveryStage> stages = List.of();
        private List<String> children = List.of();
        private List<String> postProcess = List.of();
        private boolean threads;

        Builder(String name) { this.name = name; }
        public Builder producer(String p) { this.producer = p; return this; }
        public Builder stages(List<DiscoveryStage> s) { this.stages = s; return this; }
        public Builder children(List<String> c) { this.children = c; return this; }
        public Builder children(String... c) { this.children = List.of(c); return this; }
        public Builder postProcess(List<String> p) { this.postProcess = p; return this; }
        public Builder threads(boolean t) { this.threads = t; return this; }
        public DiscoveryNode build() { return new DiscoveryNode(name, producer, stages, children, postProcess, threads); }
    }
}
