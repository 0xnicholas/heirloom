package com.heirloom.alignment.web;

import com.heirloom.core.alignment.AlignmentMap;
import com.heirloom.core.alignment.AlignmentRequest;
import com.heirloom.core.alignment.AlignmentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/alignment")
public class AlignmentResource {

    private final AlignmentService alignmentService;

    public AlignmentResource(AlignmentService alignmentService) { this.alignmentService = alignmentService; }

    @PostMapping("/tables/{tableFQN}")
    public AlignmentMap trigger(@PathVariable String tableFQN) {
        return alignmentService.align(new AlignmentRequest(tableFQN, null, false));
    }

    @GetMapping("/tables/{tableFQN}")
    public AlignmentMap get(@PathVariable String tableFQN) {
        return alignmentService.align(new AlignmentRequest(tableFQN, null, false));
    }
}
