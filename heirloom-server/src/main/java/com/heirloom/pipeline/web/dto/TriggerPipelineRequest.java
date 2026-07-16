package com.heirloom.pipeline.web.dto;

import com.heirloom.core.pipeline.PipelineTriggerType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TriggerPipelineRequest(
    @NotNull String sourceFqn,
    @NotEmpty List<String> tableFqns,
    PipelineTriggerType triggerType
) {
    public PipelineTriggerType effectiveTriggerType() {
        return triggerType != null ? triggerType : PipelineTriggerType.MANUAL;
    }
}