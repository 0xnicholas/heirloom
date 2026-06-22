# Spec: Knowledge Base Module — Phase 0.5b Enhancement

**日期**: 2026-06-22
**依赖**: Phase 0.5a 完成
**范围**: index.md/log.md 生成, status 状态机, resource→FQN 解析

---

## Phase 0.5b Scope

| # | 能力 | 优先级 |
|---|------|--------|
| 1 | `index.md` 自动生成（每目录，OKF §6） | **0.5b** |
| 2 | `log.md` 变更日志生成（根目录，OKF §7） | **0.5b** |
| 3 | status 状态机校验（draft→review→published→archived） | **0.5b** |
| 4 | frontmatter `resource` → FQN 解析 | **0.5b** |
| 5 | 管道 B（元数据自举） | 0.5c（需 DiscoveryEvent 发布） |
| 6 | Mustache 模板 + `_generated/` | 0.5c |

---

## 1. IndexGenerator

```
在每次 sync 完成后执行。
为 knowledge/ 下的每个子目录生成 index.md。
格式：OKF §6 —— 按 type 分组，每行 [title](file.md) - description。

HEIRLOOM_AUTO_START/END 标记内的内容自动覆盖，标记外的内容保留。
```

```java
public class IndexGenerator {
    public void generate(KnowledgeSource source, KnowledgeArticleJpaRepository articleJpa) {
        // 1. 查询 source 下所有 published 的 articles
        // 2. 按目录分组
        // 3. 对每目录生成 index 内容
        // 4. 写入文件（用 AUTO 标记保护人类内容）
    }
}
```

## 2. LogGenerator

```
在每次 sync 完成后执行。
在 knowledge/ 根目录的 log.md 追加变更条目。
格式：OKF §7 —— 按日期分组，**操作类型** + 描述。
仅在有实际变更（created/updated/removed > 0）时写入。
```

## 3. Status 状态机

```
draft → review → published → archived
  ↑        ↓         ↑
  └──reject┘    ┌────┘
                │ deprecate
                │
           archived

合法转换: DRAFT→[REVIEW,PUBLISHED], REVIEW→[PUBLISHED,DRAFT], PUBLISHED→[ARCHIVED,DRAFT], ARCHIVED→[PUBLISHED]
```

`KnowledgeArticle.status` 从 frontmatter 解析。同步骤增加 `KnowledgeStatus` 枚举和校验。

## 4. resource → FQN 解析

```
frontmatter 中的 resource 字段以 @ 开头 → 视为 Heirloom FQN 引用
同步时解析 @metadata_tables.xxx.yyy → EntityReference
存入 references 列表
```

---

## 5. Tests

| 组件 | 测试数 | 关键场景 |
|------|--------|---------|
| IndexGenerator | 3 | 空目录, 单文件, 多 type 分组, AUTO 标记保护 |
| LogGenerator | 2 | 有变更追加, 无变更不写入 |
| KnowledgeArticle status | 3 | 合法转换, 非法转换拒绝, 默认 published |
| ResourceResolver | 2 | @FQN 解析, 无效 FQN 忽略 |

---

## 6. Acceptance

1. sync 后自动生成各目录 index.md
2. sync 后 log.md 追加变更记录（有变更时），无变更不写入
3. KnowledgeArticle status 只能通过合法转换修改
4. frontmatter `resource: @fqn` 被解析为 EntityReference
