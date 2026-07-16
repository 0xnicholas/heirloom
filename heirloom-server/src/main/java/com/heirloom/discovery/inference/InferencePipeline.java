package com.heirloom.discovery.inference;

import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.discovery.inference.rules.TypeNameInference;
import com.heirloom.discovery.inference.rules.AbilityInference;
import com.heirloom.discovery.inference.rules.DescriptionInference;
import com.heirloom.discovery.inference.rules.FieldMapperInference;
import com.heirloom.discovery.inference.rules.RelationshipInference;
import com.heirloom.discovery.inference.rules.StateMachineInference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public class InferencePipeline {
    private static final Logger log = LoggerFactory.getLogger(InferencePipeline.class);
    private final List<InferenceRule> rules;

    public InferencePipeline() {
        this.rules = List.of(new TypeNameInference(), new FieldMapperInference(), new RelationshipInference(), new DescriptionInference(), new AbilityInference(), new StateMachineInference());
    }

    public InferencePipeline(List<InferenceRule> rules) { this.rules = rules; }

    public List<ResourceTypeProposal> infer(RawSchema schema) {
        Map<String, ResourceTypeProposal> proposals = new LinkedHashMap<>();
        for (InferenceRule rule : rules) {
            try {
                for (ResourceTypeProposal incoming : rule.infer(schema)) {
                    proposals.merge(incoming.proposedTypeName(), incoming,
                        (existing, inc) -> existing.merge(inc));
                }
            } catch (Exception e) {
                log.warn("Rule {} failed: {}", rule.getClass().getSimpleName(), e.getMessage());
            }
        }
        return new ArrayList<>(proposals.values());
    }
}
