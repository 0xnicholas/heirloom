package com.heirloom.discovery.inference;

import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.schema.domain.ResourceType;
import java.util.List;

public interface InferenceRule {
    Confidence confidence();
    List<ResourceTypeProposal> infer(RawSchema schema);

    enum Confidence { HIGH, MEDIUM, LOW, NONE }
}
