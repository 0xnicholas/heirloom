# ADR-016: ChangeEventInterceptor——自动审计

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Heirloom 需要审计所有实体变更操作。当前方案依赖开发者手动调用 Event Log——
这不可能保证一致性。需要一种机制让审计自动发生，不依赖开发者记忆。

OpenMetadata 使用 JAX-RS `ContainerResponseFilter`（`ChangeEventHandler`）。
所有非 GET 响应被拦截，自动生成 ChangeEvent 并持久化。

## 决策

**使用 Spring `ResponseBodyAdvice` 实现 ChangeEventInterceptor。**
对标 OM 的 `ChangeEventHandler`，但适配 Spring MVC 的拦截点。

### 核心机制

```java
@Component
public class ChangeEventInterceptor implements ResponseBodyAdvice<Object> {

    @Override
    public Object beforeBodyWrite(Object body, ...) {
        if (isReadOnly(httpMethod)) return body;        // 跳过 GET
        if (!(body instanceof HeirloomEntity entity))   // 只看 Entity 响应
            return body;

        ChangeEvent event = ChangeEvent.builder()
            .entityType(entity.getEntityType())
            .entityId(entity.getId())
            .eventType(resolveEventType(httpMethod))
            .actor(getCurrentActor())
            .timestamp(Instant.now())
            .build();

        eventLog.append(event);  // 独立事务
        return body;
    }
}
```

### 为什么用独立事务（REQUIRES_NEW）？

`ResponseBodyAdvice` 在 Controller 返回后、序列化前触发。此时原业务事务可能已
提交或未提交。使用 `REQUIRES_NEW` 保证审计事件一定被记录——即使业务事务回滚，
我们也需要知道「有人尝试了操作」。

### 被拒操作如何审计？

`ResponseBodyAdvice` 只在成功响应时触发。被拒操作（Authorizer 抛异常）不经过它。
解决方案：在 `@ExceptionHandler` 中显式调用 `eventLog.logRejected(...)`。

## 后果

**积极**：
- 开发者完全不需手动记审计日志
- 跳过审计在架构上不可能

**消极**：
- 独立事务可能导致审计事件被记录但业务实体未持久化（「幽灵事件」）
- `ResponseBodyAdvice` 对每个响应都触发，需要高效的 `isReadOnly()` 判断

## 参考

- OpenMetadata `ChangeEventHandler.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/events/ChangeEventHandler.java`
