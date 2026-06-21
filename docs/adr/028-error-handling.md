# ADR-028: Error Handling — Typed Exceptions + REQUIRES_NEW Audit

## 状态
Accepted

## 日期
2026-06-21

## 上下文

平台需要一致的错误处理策略——从 EntityResource → EntityService → EntityRepository
的异常传播，以及审计事件的事务隔离。

## 决策

- **Typed exceptions per layer**：UnauthorizedException (403), EntityNotFoundException (404),
  TypeValidationException (422), DataIntegrityViolationException (409), RuntimeException (500)
- **GlobalExceptionHandler** 统一转换为标准错误响应 `{ error, diagnostics[] }`
- **ChangeEventInterceptor 使用 REQUIRES_NEW 事务**：确保审计事件即使业务事务回滚也持久化
- **Discovery 部分失败**：单表 try/catch，partialSuccess=true，所有错误记录到 DiscoveryReport

## 后果

一致的错误处理——Resource 开发者不需要手动写 try/catch。代理操作尝试即使失败也被审计。

## 参考

- 设计 Spec 4b.14 节
