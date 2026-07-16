package com.heirloom.discovery.inference.rules;

import com.heirloom.core.alignment.AlignmentRequest;
import com.heirloom.core.alignment.AlignmentService;
import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import java.util.List;

public class AlignmentInference implements InferenceRule {

    private final AlignmentService alignmentService;

    public AlignmentInference(AlignmentService alignmentService) {
        this.alignmentService = alignmentService;
    }

    @Override
    public Confidence confidence() {
        return Confidence.LOW;
    }

    @Override
    public List<ResourceTypeProposal> infer(InferenceContext ctx) {
        if (ctx.rawSchema() == null || alignmentService == null) return List.of();

        var request = new AlignmentRequest(
            buildTableFQN(ctx),
            null,
            false
        );
        var alignment = alignmentService.align(request);

        return List.of();
    }

    private String buildTableFQN(InferenceContext ctx) {
        var tables = ctx.rawSchema().tables();
        if (tables != null && !tables.isEmpty()) {
            return tables.get(0).tableName();
        }
        return "unknown";
    }
}
