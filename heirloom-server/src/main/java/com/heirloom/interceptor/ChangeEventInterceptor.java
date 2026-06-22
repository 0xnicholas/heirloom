package com.heirloom.interceptor;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.entity.HeirloomEntity;
import com.heirloom.repository.EventLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ChangeEventInterceptor implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventInterceptor.class);
    private final EventLogRepository eventLog;

    public ChangeEventInterceptor(EventLogRepository eventLog) { this.eventLog = eventLog; }

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

        eventLog.append(ChangeEvent.created(entity, "system"));
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

    private String getMethod() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                log.debug("No request context available, cannot determine HTTP method");
                return null;
            }
            return attrs.getRequest().getMethod();
        } catch (Exception e) {
            log.warn("Failed to determine HTTP method for audit", e);
            return null;
        }
    }
}
