package com.heirloom.web;

import com.heirloom.schema.domain.Proposal;
import com.heirloom.schema.service.ProposalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/proposals")
public class ProposalResource {

    private final ProposalService proposalService;

    public ProposalResource(ProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Proposal> approve(@PathVariable Long id) {
        return ResponseEntity.ok(proposalService.approve(id, "admin"));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Proposal> reject(@PathVariable Long id, @RequestParam String reason) {
        return ResponseEntity.ok(proposalService.reject(id, "admin", reason));
    }
}
