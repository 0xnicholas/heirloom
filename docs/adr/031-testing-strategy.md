# ADR-031: Testing Strategy

## 状态
Accepted

## 日期
2026-06-21

## 上下文

平台包含 EntityRegistry、EntityResource、EntityRepository、ChangeEventInterceptor、
Discovery Engine 等多个子系统。需要系统的测试策略。

## 决策

**四层测试金字塔**（~93 tests for Phase 0）：
- **Unit (~40)**：TypeValidator, InferenceRules, TypeService, DiscoveryRunner (mock extractor), ChangeEventInterceptor
- **Repository Integration (~25)**：@DataJpaTest + Testcontainers PostgreSQL for all EntityRepository subclasses
- **Resource Integration (~20)**：@SpringBootTest + MockMvc for all EntityResource subclasses, auth failure scenarios
- **Discovery E2E (~8)**：Testcontainers PostgreSQL + PostgresSchemaExtractor end-to-end (scan → dual output → entity registration)

所有测试可并行执行。Testcontainers 提供隔离的 PostgreSQL 实例。

## 后果

Phase 0 有约 93 个测试覆盖核心路径。新增 Entity 类型时测试模板可复用。

## 参考

- 设计 Spec 4b.17 节
