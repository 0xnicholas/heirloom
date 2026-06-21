# ADR-023: InferencePipeline——策略链 + Proposal 合并

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Discovery 的 Phase 2 需要将 RawSchema 翻译为 ResourceTypeProposal。多条推断
规则（TypeName、FieldMapper、Relationship、Abilities、StateMachine）可能对
同一个 RawTable 产出部分 Proposal，需要合并机制。

## 决策

**采用策略链（Chain of Responsibility）+ LinkedHashMap merge 模式。**

### 核心设计

```java
public class InferencePipeline {
    private final List<InferenceRule> rules;  // 按序执行

    public List<ResourceTypeProposal> infer(RawSchema schema) {
        Map<String, ResourceTypeProposal> proposals = new LinkedHashMap<>();

        for (InferenceRule rule : rules) {
            try {
                for (ResourceTypeProposal incoming : rule.infer(schema)) {
                    proposals.merge(incoming.proposedTypeName(), incoming,
                        (existing, inc) -> existing.merge(inc));
                }
            } catch (Exception e) {
                log.warn("Rule {} failed: {}", rule.getClass(), e.getMessage());
            }
        }
        return new ArrayList<>(proposals.values());
    }
}
```

### 合并逻辑

`ResourceTypeProposal.merge()` 将 incoming 的字段（fields、relationships、
abilities、stateMachine）追加到 existing，不覆盖。置信度取 min（最保守估计）。

### 为什么用 LinkedHashMap 而非 TreeMap？

LinkedHashMap 保持插入顺序——第一条规则（TypeNameInference）先插入 Proposal
骨架，后续规则追加字段。TreeMap 按 key 排序，打乱了规则的逻辑顺序。

### 为什么单条规则失败不中断全局？

推断是启发式的——一条规则对某个表失败不应影响其他表。例如 TypeNameInference
表名规则正常，但 FieldMapperInference 对某个大 JSONB 列解析失败——应跳过该表
的字段映射，继续处理其他表。

## 后果

**积极**：
- 每条推断规则独立可测
- Phase 0 只有 2 条规则（TypeName + FieldMapper），Phase 1+ 追加 3 条——无需修改流水线
- 单个表的推断失败不影响其他表

**消极**：
- `merge()` 基于 `proposedTypeName` 字符串匹配——如果 TypeNameInference 和
  FieldMapperInference 对同一个表产出了不同的 proposedTypeName，无法合并
  （需要通过 sourceTable 做二次匹配——Phase 1 优化）

## 参考

- 设计 Spec 4b.8 节
