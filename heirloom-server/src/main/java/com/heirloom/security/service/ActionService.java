package com.heirloom.security.service;

import com.heirloom.repository.ActionRepository;
import com.heirloom.security.domain.Action;
import com.heirloom.security.pipeline.ActionPipeline;
import com.heirloom.security.pipeline.PipelineResult;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Business layer for Action execution — loads the Action, delegates to the pipeline.
 */
@Service
public class ActionService {

    private final ActionRepository actionRepo;
    private final ActionPipeline pipeline;

    public ActionService(ActionRepository actionRepo, ActionPipeline pipeline) {
        this.actionRepo = actionRepo;
        this.pipeline = pipeline;
    }

    public PipelineResult execute(String actionName, String actorType, String actorId,
                                   String actorRole, String targetResourceRid,
                                   Map<String, Object> params) {
        Action action = actionRepo.findByName(actionName)
                .filter(a -> !Boolean.TRUE.equals(a.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Action not found: " + actionName));

        return pipeline.execute(action, actorType, actorId, actorRole,
                targetResourceRid, params);
    }
}
