package com.heirloom.security.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.repository.FunctionRepository;
import com.heirloom.security.domain.Function;
import com.heirloom.security.dto.InvokeFunctionRequest;
import com.heirloom.security.dto.InvokeFunctionResponse;
import com.heirloom.security.function.FunctionExecutionException;
import com.heirloom.security.function.FunctionService;
import com.heirloom.web.EntityResource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Function entities. Standard CRUD plus
 * {@code POST /v1/functions/{name}/invoke} for sandboxed execution.
 *
 * <p>The invoke path is what makes Functions a first-class runtime primitive:
 * any consumer (Workshop view, AI Agent, internal Action validator) can call
 * the same Function by name and get the same result.
 */
@RestController
@RequestMapping("/v1/functions")
public class FunctionResource extends EntityResource<Function> {

    private final FunctionRepository functionRepo;
    private final FunctionService functionService;

    public FunctionResource(Authorizer authorizer,
                            FunctionRepository functionRepo,
                            FunctionService functionService) {
        super(EntityRegistry.FUNCTION, authorizer);
        this.functionRepo = functionRepo;
        this.functionService = functionService;
    }

    // === CRUD ===

    @GetMapping
    public ResponseEntity<List<Function>> list() {
        return ResponseEntity.ok(functionRepo.findAll());
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<Function> getByName(@PathVariable String name) {
        return functionRepo.findByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Function> create(@RequestBody Function function) {
        authorizer.authorize(Authorizer.Actor.anonymous(), entityType, "CREATE", null);
        Function saved = functionRepo.create(function);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Function> update(@PathVariable Long id, @RequestBody Function patch) {
        authorizer.authorize(Authorizer.Actor.anonymous(), entityType, "UPDATE", null);
        return functionRepo.findById(id)
                .map(existing -> {
                    if (patch.getName() != null) existing.setName(patch.getName());
                    if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
                    if (patch.getInputType() != null) existing.setInputType(patch.getInputType());
                    if (patch.getOutputType() != null) existing.setOutputType(patch.getOutputType());
                    if (patch.getCode() != null) existing.setCode(patch.getCode());
                    if (patch.getTimeoutMs() != null) existing.setTimeoutMs(patch.getTimeoutMs());
                    if (patch.getAuditEnabled() != null) existing.setAuditEnabled(patch.getAuditEnabled());
                    return functionRepo.update(existing);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        authorizer.authorize(Authorizer.Actor.anonymous(), entityType, "DELETE", null);
        functionRepo.delete(id);
        return ResponseEntity.noContent().build();
    }

    // === Invocation (the runtime primitive) ===

    @PostMapping("/name/{name}/invoke")
    public ResponseEntity<InvokeFunctionResponse> invokeByName(
            @PathVariable String name,
            @RequestBody(required = false) InvokeFunctionRequest body,
            HttpServletRequest request) {

        Map<String, Object> inputs = body != null && body.inputs() != null
                ? body.inputs() : Map.of();
        String caller = resolveCaller(request);

        Object result = functionService.invoke(name, inputs, caller);

        Function function = functionRepo.findByName(name).orElseThrow();
        return ResponseEntity.ok(new InvokeFunctionResponse(
                function.getFullyQualifiedName(),
                function.getOutputType(),
                result));
    }

    /**
     * Lookup order: {@code X-Agent-Id} header → {@code X-User} header → remote addr.
     * For audit purposes; not used for auth (that's the Authorizer's job).
     */
    private static String resolveCaller(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId != null && !agentId.isBlank()) return "agent:" + agentId;
        String user = request.getHeader("X-User");
        if (user != null && !user.isBlank()) return "user:" + user;
        return "ip:" + request.getRemoteAddr();
    }

    @ExceptionHandler(FunctionExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleFunctionError(FunctionExecutionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "function_execution_failed",
                "function", e.getFunctionName(),
                "message", e.getMessage() != null ? e.getMessage() : "unknown"));
    }
}