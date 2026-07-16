package com.heirloom.discovery.inference;

import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.profiling.ProfileReport;
import com.heirloom.core.alignment.AlignmentMap;
import java.util.List;

public record InferenceContext(
    RawSchema rawSchema,
    ProfileReport profile,
    AlignmentMap alignment,
    List<String> tableTags,
    String domainFQN
) {}
