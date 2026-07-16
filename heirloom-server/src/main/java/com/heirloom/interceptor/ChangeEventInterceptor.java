package com.heirloom.interceptor;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.core.entity.HeirloomEntity;
import com.heirloom.knowledge.service.AgentExperienceCapture;
import com.heirloom.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@Component
public class ChangeEventInterceptor implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventInterceptor.class);

    private final EventLogRepository eventLog;
    private final AgentExperienceCapture agentExperienceCapture;

    public ChangeEventInterceptor(EventLogRepository eventLog,
                                  AgentExperienceCapture agentExperienceCapture) {
        this.eventLog = eventLog;
        this.agentExperienceCapture = agentExperienceCapture;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                   MediaType selectedContentType, Class selectedConverterType,
                                   ServerHttpRequest request, ServerHttpResponse response) {
        String method = getMethod();
        if (method == null || "GET".equals(method)) return body;
        if (!(body instanceof HeirloomEntity entity)) return body;

        ChangeEvent.EventType eventType = switch (method) {
            case "POST" -> ChangeEvent.EventType.ENTITY_CREATED;
            case "PUT", "PATCH" -> ChangeEvent.EventType.ENTITY_UPDATED;
            case "DELETE" -> ChangeEvent.EventType.ENTITY_DELETED;
            default -> null;
        };
        if (eventType == null) return body;

        String caller = resolveCaller(request);
        eventLog.append(ChangeEvent.created(entity, caller));

        // Phase 3.3: auto-capture agent experience as a draft KnowledgeArticle.
        // No-op for non-agent callers; idempotent for repeat events from the
        // same actor + entity + event-type combination.
        try {
            agentExperienceCapture.captureIfAgent(entity, eventType, caller);
        } catch (Exception e) {
            // Don't let capture failures break the user-facing write.
            log.warn("Agent experience capture failed for {} {}: {}",
                    eventType, entity.getFullyQualifiedName(), e.getMessage());
        }
        return body;
    }

    public void logRejected(String entityType, String operation, String reason) {
        ChangeEvent e = new ChangeEvent();
        e.setEntityType(entityType);
        e.setEventType(ChangeEvent.EventType.ENTITY_DENIED);
        e.setDeniedOperation(operation);
        e.setDeniedReason(reason);
        e.setActor("system");
        e.setFullyQualifiedName("event." + System.currentTimeMillis());
        eventLog.append(e);
    }

    private static String resolveCaller(ServerHttpRequest request) {
        if (request == null) return "system";
        var agentId = request.getHeaders().getFirst("X-Agent-Id");
        if (agentId != null && !agentId.isBlank()) return "agent:" + agentId;
        var user = request.getHeaders().getFirst("X-User");
        if (user != null && !user.isBlank()) return "user:" + user;
        return "system";
    }

    private String getMethod() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getMethod();
        }
        return null;
    }
}