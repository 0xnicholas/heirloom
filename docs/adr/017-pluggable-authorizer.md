# ADR-017: 可插拔 Authorizer

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Phase 0 不需要授权（开发阶段）。Phase 2 需要完整的 Role → Capability → Action
授权模型。如果 Phase 0 写在 Resource 中的授权代码是硬编码的，Phase 2 切换时会
需要修改所有 Resource。

参考 OpenMetadata 的 `Authorizer` 接口：`authorize(securityContext, operationContext,
resourceContext)`。生产环境用 `DefaultAuthorizer`（RBAC），测试环境用 `NoopAuthorizer`。

## 决策

**定义 `Authorizer` 接口，Phase 0 用 `NoopAuthorizer`，Phase 2 切换到
`RoleBasedAuthorizer`。**

```java
public interface Authorizer {
    void authorize(Actor actor, String entityType, String operation, String entityFQN);
    boolean isAdmin(Actor actor);
}
```

### Phase 0 实现

```java
@Component @Profile("dev")
public class NoopAuthorizer implements Authorizer {
    public void authorize(...) { /* allow all */ }
    public boolean isAdmin(...) { return true; }
}
```

### Phase 2 实现（预留）

```java
@Component @Profile("prod")
public class RoleBasedAuthorizer implements Authorizer {
    public void authorize(Actor actor, String entityType, String operation, String fqn) {
        // 1. 解析 actor 的 Role
        // 2. 从 Role 派生 Capability
        // 3. 检查 Capability 是否覆盖 operation + entityType + fqn
        // 4. 放行或抛出 UnauthorizedException
    }
}
```

### 为什么 Authorizer 放在 EntityResource 而非 EntityRepository？

授权是 API 层面的关注点——在请求进入时检查。Repository 的 `create()` 方法可能被
内部调用（如 DiscoveryService.runDiscovery() 自动注册 ResourceType），不应重复
授权。因此 Authorizer 在 Resource 层调用。

## 后果

**积极**：
- Phase 0 到 Phase 2 的无缝切换，Resource 代码不需要修改
- Spring `@Profile` 支持按环境切换实现
- 接口简单——三个参数覆盖所有授权场景

**消极**：
- `operation` 参数是字符串（"CREATE"、"DELETE" 等），编译时无类型安全
- Phase 2 的 `RoleBasedAuthorizer` 实现复杂度较高（需要查询 Role/Capability 存储）

## 参考

- OpenMetadata `Authorizer.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/security/Authorizer.java`
