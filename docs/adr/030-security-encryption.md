# ADR-030: Security — Connection Config Encryption

## 状态
Accepted

## 日期
2026-06-21

## 上下文

DatabaseService.connectionConfig 包含数据库密码等敏感信息，必须加密存储。

## 决策

- **AES-256 加密**（key 来自环境变量或 KMS）
- **JPA @Converter** 自动加密/解密——应用层透明
- **@JsonIgnore** on connectionConfig——API 响应永远不返回
- SchemaExtractor.prepare() 中解密后使用

Phase 2+：集成 KMS（AWS KMS / HashiCorp Vault）。

## 后果

密码不以明文存储。API 响应不含敏感信息。开发时可用环境变量 key。

## 参考

- 设计 Spec 4b.17 节
