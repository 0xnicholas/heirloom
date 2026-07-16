package com.heirloom.discovery.inference;

import com.heirloom.schema.domain.ResourceType;
import java.util.List;

public interface InferenceRule {
    Confidence confidence();
    List<ResourceTypeProposal> infer(InferenceContext ctx);

    enum Confidence { HIGH, MEDIUM, LOW, NONE }
}
