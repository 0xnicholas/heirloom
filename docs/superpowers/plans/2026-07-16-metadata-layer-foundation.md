# Heirloom 元数据层基础建设 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Heirloom「第一层」——元数据目录基础能力：模块化拆分 → 实体补齐（Classification/Tag/Domain/Column）→ Profiling 引擎 → InferencePipeline 升级

**Architecture:** 先拆分为 `heirloom-core`（纯接口 + record）+ `heirloom-connector`（PG/MySQL 提取器）+ `heirloom-server`（组装层），再在 core 中定义元数据接口，在 server 中实现 JPA + JDBC，最后升级推理管线

**Tech Stack:** Java 21, Spring Boot 3.5.4, Spring Data JPA, PostgreSQL, Flyway, H2 (test), Testcontainers, Maven 多模块

**Spec:** `docs/superpowers/specs/2026-07-16-metadata-layer-foundation.md`

---

## 文件结构总览

### 新建文件

```
heirloom/
├── pom.xml                                            # 聚合 POM (<packaging>pom</packaging>)
│
├── heirloom-core/
│   ├── pom.xml                                        # 零外部依赖（无 Spring Boot）
│   └── src/main/java/com/heirloom/core/
│       ├── entity/
│       │   ├── HeirloomEntity.java                    # 从 heirloom-server 迁移
│       │   ├── EntityRegistry.java                     # 从 heirloom-server 迁移
│       │   └── EntityRegistration.java                 # 从 heirloom-server 迁移
│       ├── repository/
│       │   └── EntityRepository.java                   # 从 heirloom-server 迁移
│       ├── discovery/
│       │   ├── SchemaExtractor.java                    # SPI 接口
│       │   ├── SchemaExtractorRegistry.java            # SPI 注册表
│       │   ├── DiscoveryConfig.java                    # 从 heirloom-server 迁移
│       │   └── model/
│       │       ├── RawSchema.java
│       │       ├── RawTable.java
│       │       ├── RawColumn.java
│       │       ├── RawConstraint.java
│       │       └── RawRelationship.java
│       ├── query/
│       │   ├── SemanticQuery.java
│       │   ├── QueryParser.java
│       │   ├── AggregationQuery.java
│       │   ├── QueryParseException.java
│       │   ├── GeneratedSql.java
│       │   └── SqlGenerator.java                       # 接口
│       ├── metadata/
│       │   ├── Classification.java                     # 接口
│       │   ├── Tag.java                                # 接口
│       │   ├── Domain.java                             # 接口
│       │   ├── ColumnDef.java                          # record
│       │   └── TableProfileDef.java                    # 接口
│       ├── profiling/
│       │   ├── ProfilingService.java                   # 接口
│       │   ├── ProfileReport.java                      # record
│       │   ├── ColumnProfileResult.java                # record
│       │   ├── ValueFrequency.java                     # record
│       │   ├── DataClass.java                          # enum
│       │   └── SamplingStrategy.java                   # 接口
│       └── alignment/
│           ├── AlignmentService.java                   # 接口
│           ├── AlignmentRequest.java                   # record
│           ├── AlignmentMap.java                       # record
│           ├── FieldAlignment.java                     # record
│           ├── SemanticTarget.java                     # record
│           ├── AlignmentSignalType.java                # enum
│           ├── AlignmentSignal.java                    # record
│           └── NewTypeSuggestion.java                  # record
│
├── heirloom-connector/
│   ├── pom.xml                                        # 父 POM
│   ├── heirloom-connector-postgres/
│   │   ├── pom.xml
│   │   └── src/main/java/com/heirloom/connector/postgres/
│   │       └── PostgresSchemaExtractor.java            # 从 heirloom-server 迁移
│   └── heirloom-connector-mysql/
│       ├── pom.xml
│       └── src/main/java/com/heirloom/connector/mysql/
│           └── MySqlSchemaExtractor.java               # 从 heirloom-server 迁移
│
└── heirloom-server/                                     # 现有模块，重组
    ├── pom.xml                                         # 修改：依赖 heirloom-core + connectors
    └── src/main/java/com/heirloom/
        ├── metadata/
        │   ├── domain/
        │   │   ├── TableEntity.java                    # 增强（6 新字段）
        │   │   ├── DomainEntity.java                   # 增强（parentFQN）
        │   │   ├── TableProfileEntity.java             # 增强（5 新字段）
        │   │   ├── ClassificationEntity.java           # 新建
        │   │   ├── TagEntity.java                      # 新建
        │   │   ├── ColumnProfileEntity.java            # 新建
        │   │   ├── LineageEntity.java                  # 不动
        │   │   └── ColumnDefParser.java                # 新建（columnsJson 兼容解析）
        │   ├── repository/
        │   │   ├── ClassificationJpaRepository.java    # 新建
        │   │   ├── ClassificationRepository.java       # 新建
        │   │   ├── TagJpaRepository.java               # 新建
        │   │   ├── TagRepository.java                  # 新建
        │   │   ├── ColumnProfileJpaRepository.java     # 新建
        │   │   └── ColumnProfileRepository.java        # 新建
        │   └── web/
        │       ├── ColumnResource.java                 # 新建
        │       ├── ClassificationResource.java          # 新建
        │       ├── TagResource.java                    # 新建
        │       ├── ProfilingResource.java              # 新建
        │       └── AlignmentResource.java              # 新建
├── profiling/
│   └── service/
│       ├── ProfilingServiceImpl.java            # 新建（JDBC 实现）
│       ├── PostgresSamplingStrategy.java        # 新建
│       ├── QualityScorer.java                   # 新建（5 维加权评分）
│       └── DataClassInferrer.java               # 新建（8 种 DataClass 规则推断）
        ├── alignment/
        │   └── service/
        │       └── AlignmentServiceImpl.java            # 新建
        ├── discovery/
        │   └── inference/
        │       ├── InferenceContext.java                # 新建 record
        │       ├── InferencePipeline.java              # 修改签名
        │       └── rules/
        │           ├── InferenceRule.java              # 修改签名
        │           ├── AlignmentInference.java          # 新建
        │           └── ... (6 条规则修改签名 + 增强)   # 修改
        ├── service/
        │   └── DiscoveryService.java                   # 修改（插入 Profiling 步骤）
        └── web/
            ├── DiscoveryResource.java                  # 修改（加 profile 参数）
            └── TableResource.java                      # 新建（或修改现有）
```

### Flyway 新建

```
src/main/resources/db/migration/
├── V17__enhance_metadata_tables.sql
├── V18__create_classifications_tags.sql
├── V19__add_domain_parent.sql
├── V20__create_column_profiles.sql
└── V21__enhance_table_profiles.sql
```

---

## Phase 0: 模块化（目标：heirloom-core 不依赖 Spring Boot）

### Task 0.0: 创建聚合 POM

**Files:**
- Create: `pom.xml` (heirloom 根目录)

- [ ] **Step 1: 在根目录创建聚合 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.heirloom</groupId>
    <artifactId>heirloom-parent</artifactId>
    <version>0.1.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>heirloom-server</module>
    </modules>
</project>
```

Run: `mvn validate` from root. Expected: BUILD SUCCESS.

> **注**: 根 POM 初始只列 `heirloom-server` 一个已有模块。后续每创建一个新模块（heirloom-core, heirloom-connector）再逐步添加，保证增量构建每一步都可验证。

- [ ] **Step 2: Commit**

```bash
git add pom.xml
git commit -m "chore: add Maven reactor POM for multi-module build"
```

---

### Task 0.1: 创建 heirloom-core 模块

**Files:**
- Create: `heirloom-core/pom.xml`
- Create: `heirloom-core/src/main/java/com/heirloom/core/.gitkeep` (temporary)

- [ ] **Step 1: 创建 heirloom-core/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.heirloom</groupId>
        <artifactId>heirloom-parent</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>heirloom-core</artifactId>
    <packaging>jar</packaging>
    <name>Heirloom Core</name>
    <description>Semantic core — interfaces, records, enums. No Spring Boot dependency.</description>

    <dependencies>
        <!-- Jakarta Validation annotations (zero Spring dependency) -->
        <dependency>
            <groupId>jakarta.validation</groupId>
            <artifactId>jakarta.validation-api</artifactId>
            <version>3.1.0</version>
        </dependency>

        <!-- Test only -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.12.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>21</release>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建 .gitkeep 防止空源码目录报错**

```bash
mkdir -p heirloom-core/src/main/java/com/heirloom/core
mkdir -p heirloom-core/src/test/java/com/heirloom/core
```

- [ ] **Step 3: 更新根 pom.xml 添加 heirloom-core 模块**

```xml
    <modules>
        <module>heirloom-core</module>
    </modules>
```

Run: `mvn compile` from root. Expected: BUILD SUCCESS.

- [ ] **Step 4: 验证 ArchUnit 测试**

```bash
# heirloom-core 不应依赖 spring-boot、spring-web、jakarta.persistence
```

> 注：ArchUnit 测试在 heirloom-server 当前位置暂不执行——等模块迁移完成后统一加。

- [ ] **Step 5: Commit**

```bash
git add heirloom-core/ pom.xml
git commit -m "feat: create heirloom-core module (zero external deps)"
```

---

### Task 0.2: 迁移 Entity 基类 + EntityRepository

**Files:**
- Create: `heirloom-core/src/main/java/com/heirloom/core/entity/HeirloomEntity.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/entity/EntityRegistry.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/entity/EntityRegistration.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/repository/EntityRepository.java`
- Modify: `heirloom-server/.../entity/HeirloomEntity.java` → 删除（import from core）
- Modify: `heirloom-server/.../entity/EntityRegistry.java` → 删除（import from core）
- Modify: `heirloom-server/.../entity/EntityRegistration.java` → 删除（import from core）
- Modify: `heirloom-server/.../repository/EntityRepository.java` → 改为继承 core 版本

- [ ] **Step 1: 复制 HeirloomEntity.java 到 heirloom-core**

```bash
cp heirloom-server/src/main/java/com/heirloom/entity/HeirloomEntity.java \
   heirloom-core/src/main/java/com/heirloom/core/entity/HeirloomEntity.java
```

然后修改 package: `package com.heirloom.entity;` → `package com.heirloom.core.entity;`

- [ ] **Step 2: 复制 EntityRegistry.java 到 heirloom-core**

```bash
cp heirloom-server/src/main/java/com/heirloom/entity/EntityRegistry.java \
   heirloom-core/src/main/java/com/heirloom/core/entity/EntityRegistry.java
```

修改 package 同上。保留所有 import（Spring `@Component` 注释去掉——core 不能依赖 Spring。改为纯静态 Map）。

EntityRegistry 的核心逻辑是 `ConcurrentHashMap<String, EntityRegistration>` + `register()` + `getRepository()` 等静态方法。去掉 `@Component` 和 `@PostConstruct` 引用，只保留静态注册逻辑。Spring 组件注册方式移到 `heirloom-server` 侧的 wrapper。

- [ ] **Step 3: 复制 EntityRegistration.java 到 heirloom-core**

```bash
cp heirloom-server/src/main/java/com/heirloom/entity/EntityRegistration.java \
   heirloom-core/src/main/java/com/heirloom/core/entity/EntityRegistration.java
```

修改 package。

- [ ] **Step 4: 复制 EntityRepository.java 到 heirloom-core**

```bash
cp heirloom-server/src/main/java/com/heirloom/repository/EntityRepository.java \
   heirloom-core/src/main/java/com/heirloom/core/repository/EntityRepository.java
```

修改 package 为 `com.heirloom.core.repository`。去掉所有 Spring 注解（`@Component`、`@Transactional`、`@Autowired`）。保留核心模板方法：`create()`, `update()`, `delete()`, `prepare()`, `setFQN()`, `prepareInternal()`, `storeEntity()`, `storeRelationships()` 的抽象签名。具体 JPA 实现留 `heirloom-server` 子类。

`heirloom-server` 的现有 `EntityRepository.java` 改为 `extends com.heirloom.core.repository.EntityRepository<EntityType>`，重新加回 Spring 注解。

- [ ] **Step 5: heirloom-server 改为 import from core**

在 heirloom-server 中，三个原文件替换为：

```java
// HeirloomEntity.java → 删除或改为 typealias
// 真正的接口在 heirloom-core，heirloom-server 的 Entity 类通过 import 引用
```

实际策略：删除 heirloom-server 中的原始文件，所有引用处改为 `import com.heirloom.core.entity.HeirloomEntity;`。

- [ ] **Step 6: heirloom-server 添加 heirloom-core 依赖**

修改 `heirloom-server/pom.xml`，添加 dependency:

```xml
<dependency>
    <groupId>com.heirloom</groupId>
    <artifactId>heirloom-core</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 7: 编译验证**

```bash
mvn compile
```

预期：BUILD SUCCESS。所有 import 路径更新完成。

- [ ] **Step 8: Run tests**

```bash
mvn test
```

预期：192 tests PASS。

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: migrate HeirloomEntity/EntityRegistry/EntityRegistration/EntityRepository to heirloom-core"
```

---

### Task 0.3: 迁移 Discovery 模型类

**Files:**
- Create: `heirloom-core/src/main/java/com/heirloom/core/discovery/SchemaExtractor.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/discovery/SchemaExtractorRegistry.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/discovery/DiscoveryConfig.java`
- Create: `heirloom-core/src/main/java/.../discovery/model/RawSchema.java`
- Create: `heirloom-core/src/main/java/.../discovery/model/RawTable.java`
- Create: `heirloom-core/src/main/java/.../discovery/model/RawColumn.java`
- Create: `heirloom-core/src/main/java/.../discovery/model/RawConstraint.java`
- Create: `heirloom-core/src/main/java/.../discovery/model/RawRelationship.java`
- Delete: `heirloom-server/.../discovery/extractor/SchemaExtractor.java` (迁移后删除)
- Delete: `heirloom-server/.../discovery/model/RawSchema.java` 等 5 个文件

- [ ] **Step 1: 复制 5 个 Raw* model 文件到 heirloom-core**

```bash
for f in RawSchema RawTable RawColumn RawConstraint RawRelationship; do
  cp heirloom-server/src/main/java/com/heirloom/discovery/model/${f}.java \
     heirloom-core/src/main/java/com/heirloom/core/discovery/model/${f}.java
done
```

全部修改 package: `com.heirloom.discovery.model` → `com.heirloom.core.discovery.model`

- [ ] **Step 2: 复制 DiscoveryConfig + SchemaExtractor 到 heirloom-core**

```bash
cp heirloom-server/src/main/java/com/heirloom/discovery/extractor/DiscoveryConfig.java \
   heirloom-core/src/main/java/com/heirloom/core/discovery/DiscoveryConfig.java

cp heirloom-server/src/main/java/com/heirloom/discovery/extractor/SchemaExtractor.java \
   heirloom-core/src/main/java/com/heirloom/core/discovery/SchemaExtractor.java
```

修改 package。

- [ ] **Step 3: 创建 SchemaExtractorRegistry.java**

```java
package com.heirloom.core.discovery;

import java.util.*;

public class SchemaExtractorRegistry {
    private static final Map<String, Supplier<SchemaExtractor>> factories = new ConcurrentHashMap<>();

    public static void register(String sourceType, Supplier<SchemaExtractor> factory) {
        factories.put(sourceType.toLowerCase(), factory);
    }

    public static Optional<SchemaExtractor> create(String sourceType) {
        var factory = factories.get(sourceType.toLowerCase());
        return factory != null ? Optional.of(factory.get()) : Optional.empty();
    }

    public static Set<String> supportedSourceTypes() {
        return Set.copyOf(factories.keySet());
    }
}
```

- [ ] **Step 4: 更新 heirloom-server import 路径**

全部引用处改为 `import com.heirloom.core.discovery.model.*;` 等。

- [ ] **Step 5: 编译 + 测试**

```bash
mvn compile && mvn test
```

预期：BUILD SUCCESS, 192 PASS。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: migrate Discovery models and SchemaExtractor SPI to heirloom-core"
```

---

### Task 0.4: 迁移 Query 类

**Files:**
- Move: `SemanticQuery.java`, `QueryParser.java`, `AggregationQuery.java`, `GeneratedSql.java`, `QueryParseException.java` → `heirloom-core/.../query/`
- Keep in server: `SqlGenerator.java` (依赖 JPA/JDBC，留在 server 实现接口)

- [ ] **Step 1: 复制 5 个文件到 heirloom-core**

```bash
for f in SemanticQuery QueryParser AggregationQuery GeneratedSql QueryParseException; do
  cp heirloom-server/src/main/java/com/heirloom/query/${f}.java \
     heirloom-core/src/main/java/com/heirloom/core/query/${f}.java
done
```

全部修改 package。

- [ ] **Step 2: SqlGenerator 提取接口**

在 `heirloom-core` 创建接口：

```java
// heirloom-core/src/main/java/com/heirloom/core/query/SqlGenerator.java
package com.heirloom.core.query;

public interface SqlGenerator {
    GeneratedSql generate(SemanticQuery query);
}
```

heirloom-server 的现有 `SqlGenerator.java` 改为 `implements com.heirloom.core.query.SqlGenerator`。

- [ ] **Step 3: 编译 + 测试**

```bash
mvn compile && mvn test
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: migrate query model classes to heirloom-core, extract SqlGenerator interface"
```

---

### Task 0.5: 创建 Connector 模块

**Files:**
- Create: `heirloom-connector/pom.xml`
- Create: `heirloom-connector/heirloom-connector-postgres/pom.xml`
- Create: `heirloom-connector/heirloom-connector-postgres/src/main/java/.../PostgresSchemaExtractor.java`
- Create: `heirloom-connector/heirloom-connector-mysql/pom.xml`
- Create: `heirloom-connector/heirloom-connector-mysql/src/main/java/.../MySqlSchemaExtractor.java`
- Modify: `heirloom-server` pom.xml — 添加两个 connector 依赖

- [ ] **Step 1: 创建 heirloom-connector/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="..." xmlns:xsi="..." xsi:schemaLocation="...">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.heirloom</groupId>
        <artifactId>heirloom-parent</artifactId>
        <version>0.1.0</version>
    </parent>
    <artifactId>heirloom-connector</artifactId>
    <packaging>pom</packaging>
    <modules>
        <module>heirloom-connector-postgres</module>
        <module>heirloom-connector-mysql</module>
    </modules>
    <dependencies>
        <dependency>
            <groupId>com.heirloom</groupId>
            <artifactId>heirloom-core</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 创建 heirloom-connector-postgres/pom.xml**

```xml
<project>
    <parent>heirloom-connector</parent>
    <artifactId>heirloom-connector-postgres</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>42.7.5</version>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 迁移 PostgresSchemaExtractor**

```bash
cp heirloom-server/src/main/java/com/heirloom/discovery/extractor/postgres/PostgresSchemaExtractor.java \
   heirloom-connector/heirloom-connector-postgres/src/main/java/com/heirloom/connector/postgres/PostgresSchemaExtractor.java
```

修改 package 为 `com.heirloom.connector.postgres`，实现 `import com.heirloom.core.discovery.SchemaExtractor;`。

- [ ] **Step 4: 更新根 pom.xml 加 connector 模块**

```xml
        <module>heirloom-connector</module>
```

- [ ] **Step 5: heirloom-server 添加 connector 依赖**

```xml
<dependency>
    <groupId>com.heirloom</groupId>
    <artifactId>heirloom-connector-postgres</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 6: Spring Boot 自动配置注册**

在 `heirloom-server` 中创建 `ConnectorAutoConfiguration.java`：

```java
@Configuration
public class ConnectorAutoConfiguration {
    @Bean
    public PostgresSchemaExtractor postgresSchemaExtractor(DataSource dataSource) {
        var extractor = new PostgresSchemaExtractor(dataSource);
        SchemaExtractorRegistry.register("postgresql", () -> extractor);
        return extractor;
    }
}
```

- [ ] **Step 7: 重复 Step 2-6 对 MySQL connector**

包名 `com.heirloom.connector.mysql`。

- [ ] **Step 8: 编译 + 测试**

```bash
mvn compile && mvn test
```

预期：192 tests PASS。所有模块编译通过。

- [ ] **Step 9: ArchUnit 验证**

在 `heirloom-core` 的 test 中添加：

```java
@Test
void coreModule_shouldNotDependOnSpringBoot() {
    var classes = new ClassFileImporter()
        .importPackages("com.heirloom.core");
    var rule = ArchRuleDefinition.noClasses()
        .should().dependOnClassesThat()
        .resideInAnyPackage("org.springframework..");
    rule.check(classes);
}
```

添加 `com.tngtech.archunit:archunit-junit5:1.4.0` test 依赖到 heirloom-core pom.xml。

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: create heirloom-connector modules (postgres, mysql) with SchemaExtractor SPI"
```

---

### Task 0.6: 最终清理 + 验证

- [ ] **Step 1: 删除 heirloom-server 中已迁移的类**

```bash
# 删除已迁移到 heirloom-core 的原始位置
rm heirloom-server/src/main/java/com/heirloom/entity/HeirloomEntity.java
rm heirloom-server/src/main/java/com/heirloom/entity/EntityRegistry.java
rm heirloom-server/src/main/java/com/heirloom/entity/EntityRegistration.java
rm heirloom-server/src/main/java/com/heirloom/discovery/model/*.java
rm heirloom-server/src/main/java/com/heirloom/discovery/extractor/SchemaExtractor.java
rm heirloom-server/src/main/java/com/heirloom/discovery/extractor/DiscoveryConfig.java
rm heirloom-server/src/main/java/com/heirloom/query/SemanticQuery.java
rm heirloom-server/src/main/java/com/heirloom/query/QueryParser.java
rm heirloom-server/src/main/java/com/heirloom/query/AggregationQuery.java
rm heirloom-server/src/main/java/com/heirloom/query/GeneratedSql.java
rm heirloom-server/src/main/java/com/heirloom/query/QueryParseException.java
```

- [ ] **Step 2: 处理 EntityRegistry 的 Spring 集成**

在 `heirloom-server` 侧创建 wrapper：

```java
// heirloom-server/.../entity/EntityRegistryInitializer.java
@Component
public class EntityRegistryInitializer {
    // No logic needed — EntityRegistry is now static
    // This just ensures the class is loaded for Spring context scanning
}
```

- [ ] **Step 3: 全量测试**

```bash
mvn clean test
```

预期：192 tests PASS。ArchUnit 测试 PASS。

- [ ] **Step 4: Phase 0 完成 Commit**

```bash
git add -A
git commit -m "chore: complete Phase 0 — module migration cleanup, ArchUnit verification"
```

**Phase 0 退出标准达成：** heirloom-core 零 Spring 依赖，3 个模块独立编译，192 测试全部通过。

---

## Phase 1: 元数据实体补齐

### Task 1.0: heirloom-core 定义元数据接口

**Files:**
- Create: `heirloom-core/src/main/java/.../metadata/Classification.java`
- Create: `heirloom-core/src/main/java/.../metadata/Tag.java`
- Create: `heirloom-core/src/main/java/.../metadata/Domain.java`
- Create: `heirloom-core/src/main/java/.../metadata/ColumnDef.java`
- Create: `heirloom-core/src/main/java/.../metadata/TableProfileDef.java`

- [ ] **Step 1: 创建 Classification.java**

```java
package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;

public interface Classification extends HeirloomEntity {
    String getName();
    String getDescription();
}
```

- [ ] **Step 2: 创建 Tag.java**

```java
package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;

public interface Tag extends HeirloomEntity {
    String getName();
    String getClassificationFQN();
    String getParentFQN();
    String getStyle();
    String getDescription();
}
```

- [ ] **Step 3: 创建 Domain.java**

```java
package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;

public interface Domain extends HeirloomEntity {
    String getName();
    String getParentFQN();
    String getDescription();
    String getOwner();
}
```

- [ ] **Step 4: 创建 ColumnDef.java**

```java
package com.heirloom.core.metadata;

import java.util.List;

public record ColumnDef(
    String name,
    String dataType,
    Integer dataLength,
    Integer numericPrecision,
    Integer numericScale,
    boolean nullable,
    String defaultValue,
    String comment,
    Integer ordinalPosition,
    List<String> tags
) {}
```

- [ ] **Step 5: 创建 TableProfileDef.java**

```java
package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;
import java.time.Instant;

public interface TableProfileDef extends HeirloomEntity {
    String getTableFQN();
    Long getRowCount();
    Long getSizeInBytes();
    Instant getFreshness();
    Instant getProfiledAt();
    Long getProfilingDurationMs();
    Long getNullCount();
    Long getDistinctCount();
    Long getDuplicateRowCount();
    String getColumnProfiles();
    Double getOverallQualityScore();
}
```

- [ ] **Step 6: 编译 heirloom-core**

```bash
mvn compile -pl heirloom-core
```

- [ ] **Step 7: Commit**

```bash
git add heirloom-core/
git commit -m "feat: define metadata interfaces in heirloom-core (Classification, Tag, Domain, ColumnDef, TableProfileDef)"
```

---

### Task 1.1: Flyway V17 — 增强 metadata_tables

**Files:**
- Create: `heirloom-server/src/main/resources/db/migration/V17__enhance_metadata_tables.sql`

- [ ] **Step 1: 写 Flyway 脚本**

```sql
ALTER TABLE metadata_tables
  ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]',
  ADD COLUMN IF NOT EXISTS domain_fqn VARCHAR(256),
  ADD COLUMN IF NOT EXISTS constraints JSONB NOT NULL DEFAULT '[]',
  ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(32) NOT NULL DEFAULT 'Created',
  ADD COLUMN IF NOT EXISTS certification JSONB;

CREATE INDEX IF NOT EXISTS idx_tables_domain ON metadata_tables(domain_fqn);
CREATE INDEX IF NOT EXISTS idx_tables_tags ON metadata_tables USING GIN (tags jsonb_path_ops);
```

- [ ] **Step 2: 验证迁移**

```bash
mvn flyway:migrate -pl heirloom-server
```

预期：Flyway 执行 V17 成功。

- [ ] **Step 3: Commit**

```bash
git add heirloom-server/src/main/resources/db/migration/V17__enhance_metadata_tables.sql
git commit -m "feat: add Flyway V17 — enhance metadata_tables with tags, domain, constraints, lifecycle, certification"
```

---

### Task 1.2: Flyway V18 — 创建 classifications + tags 表

**Files:**
- Create: `heirloom-server/src/main/resources/db/migration/V18__create_classifications_tags.sql`

- [ ] **Step 1: 写 Flyway 脚本**

```sql
CREATE TABLE IF NOT EXISTS metadata_classifications (
    id BIGSERIAL PRIMARY KEY,
    fully_qualified_name VARCHAR(256) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    change_hash VARCHAR(64),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    owner VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS metadata_tags (
    id BIGSERIAL PRIMARY KEY,
    fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
    name VARCHAR(256) NOT NULL,
    classification_fqn VARCHAR(256) NOT NULL,
    parent_fqn VARCHAR(512),
    style JSONB,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    change_hash VARCHAR(64),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE metadata_tags
  ADD CONSTRAINT fk_tags_classification
  FOREIGN KEY (classification_fqn)
  REFERENCES metadata_classifications(fully_qualified_name);

CREATE INDEX IF NOT EXISTS idx_tags_classification ON metadata_tags(classification_fqn);
CREATE INDEX IF NOT EXISTS idx_tags_parent ON metadata_tags(parent_fqn);
```

- [ ] **Step 2: 验证迁移**

```bash
mvn flyway:migrate -pl heirloom-server
```

- [ ] **Step 3: Commit**

```bash
git add heirloom-server/src/main/resources/db/migration/V18__create_classifications_tags.sql
git commit -m "feat: add Flyway V18 — create metadata_classifications and metadata_tags tables"
```

---

### Task 1.3: Flyway V19 — Domain parentFQN

**Files:**
- Create: `heirloom-server/src/main/resources/db/migration/V19__add_domain_parent.sql`

- [ ] **Step 1: 写 Flyway 脚本**

```sql
ALTER TABLE metadata_domains
  ADD COLUMN IF NOT EXISTS parent_fqn VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_domains_parent ON metadata_domains(parent_fqn);
```

- [ ] **Step 2: 验证 + Commit**

```bash
mvn flyway:migrate -pl heirloom-server
git add heirloom-server/src/main/resources/db/migration/V19__add_domain_parent.sql
git commit -m "feat: add Flyway V19 — add parent_fqn to metadata_domains"
```

---

### Task 1.4: 实现 ClassificationEntity + TagEntity

**Files:**
- Create: `heirloom-server/.../metadata/domain/ClassificationEntity.java`
- Create: `heirloom-server/.../metadata/domain/TagEntity.java`
- Create: `heirloom-server/.../metadata/repository/ClassificationJpaRepository.java`
- Create: `heirloom-server/.../metadata/repository/ClassificationRepository.java`
- Create: `heirloom-server/.../metadata/repository/TagJpaRepository.java`
- Create: `heirloom-server/.../metadata/repository/TagRepository.java`

- [ ] **Step 1: ClassificationEntity.java**

```java
package com.heirloom.metadata.domain;

import com.heirloom.core.entity.HeirloomEntity;
import com.heirloom.core.metadata.Classification;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "metadata_classifications")
public class ClassificationEntity implements HeirloomEntity, Classification {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String name;
    private String description;
    private String owner;
    @Version private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    // getters + setters for all fields
    public Long getId() { return id; }
    public String getEntityType() { return "classification"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String fqn) { this.fullyQualifiedName = fqn; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getOwner() { return owner; }
    public void setOwner(String o) { this.owner = o; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; }
    public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: ClassificationJpaRepository.java**

```java
package com.heirloom.metadata.repository;

import com.heirloom.metadata.domain.ClassificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ClassificationJpaRepository extends JpaRepository<ClassificationEntity, Long> {
    Optional<ClassificationEntity> findByFullyQualifiedName(String fqn);
    Optional<ClassificationEntity> findByName(String name);
}
```

- [ ] **Step 3: ClassificationRepository.java**

```java
package com.heirloom.metadata.repository;

import com.heirloom.metadata.domain.ClassificationEntity;
import com.heirloom.repository.EntityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

@Repository
public class ClassificationRepository extends EntityRepository<ClassificationEntity> {

    private final ClassificationJpaRepository jpa;

    public ClassificationRepository(ClassificationJpaRepository jpa) {
        super(jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register("classification", new EntityRegistration(
            "classification", ClassificationEntity.class, this, null,
            "{name}", "/v1/classifications"
        ));
    }

    @Override
    protected void setFullyQualifiedName(ClassificationEntity entity) {
        entity.setFullyQualifiedName(entity.getName());
    }

    @Override
    protected void prepareInternal(ClassificationEntity entity) {
        // no-op
    }
}
```

- [ ] **Step 4: 重复 Step 1-3 对 TagEntity**

`TagEntity` 实现 `HeirloomEntity` + `com.heirloom.core.metadata.Tag`。JPA 映射 `metadata_tags` 表。Repository 同模式。

- [ ] **Step 5: 写 Repository 单元测试**

```java
// heirloom-server/src/test/java/com/heirloom/metadata/repository/ClassificationRepositoryTest.java
@DataJpaTest
class ClassificationRepositoryTest {
    @Autowired private ClassificationJpaRepository jpa;
    private ClassificationRepository repo;

    @BeforeEach
    void setUp() { repo = new ClassificationRepository(jpa); }

    @Test
    void shouldCreateAndFind() {
        var entity = new ClassificationEntity();
        entity.setName("PII");
        entity.setDescription("Personally Identifiable Information");
        var created = repo.create(entity);
        assertNotNull(created.getId());
        assertEquals("PII", created.getFullyQualifiedName());
    }
}
```

- [ ] **Step 6: 运行 Repository 测试**

```bash
mvn test -pl heirloom-server -Dtest="ClassificationRepositoryTest,TagRepositoryTest"
```

预期：全部 PASS。

- [ ] **Step 7: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/metadata/
git add heirloom-server/src/test/
git commit -m "feat: implement ClassificationEntity and TagEntity with JPA repositories"
```

---

### Task 1.5: 增强 TableEntity + DomainEntity

**Files:**
- Modify: `heirloom-server/.../metadata/domain/TableEntity.java` — 加 6 字段
- Modify: `heirloom-server/.../metadata/domain/DomainEntity.java` — 加 parentFQN

- [ ] **Step 1: TableEntity 加 6 新字段**

在 `TableEntity` 中添加字段声明 + getters/setters：

```java
// 添加在 columnsJson 之后、version 之前
@Column(columnDefinition = "jsonb") private String tags = "[]";
@Column(name = "domain_fqn") private String domainFQN;
@Column(columnDefinition = "jsonb") private String constraints = "[]";
@Column(name = "source_hash") private String sourceHash;
private String lifecycle = "Created";
@Column(columnDefinition = "jsonb") private String certification;
```

加上对应 getters/setters。

- [ ] **Step 2: DomainEntity 加 parentFQN**

```java
@Column(name = "parent_fqn") private String parentFQN;
// + getter/setter
```

- [ ] **Step 3: 运行现有测试确认不破坏**

```bash
mvn test -pl heirloom-server
```

预期：全部 PASS（增强字段都有 DEFAULT，不影响现有测试）。

- [ ] **Step 4: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/metadata/domain/TableEntity.java
git add heirloom-server/src/main/java/com/heirloom/metadata/domain/DomainEntity.java
git commit -m "feat: enhance TableEntity (6 fields) and DomainEntity (parentFQN)"
```

---

### Task 1.6: ColumnDefParser + ColumnProfileEntity + Flyway V20

**Files:**
- Create: `heirloom-server/.../metadata/domain/ColumnDefParser.java`
- Create: `heirloom-server/.../metadata/domain/ColumnProfileEntity.java`
- Create: `heirloom-server/.../metadata/repository/ColumnProfileJpaRepository.java`
- Create: `heirloom-server/.../metadata/repository/ColumnProfileRepository.java`
- Create: `heirloom-server/src/main/resources/db/migration/V20__create_column_profiles.sql`

- [ ] **Step 1: V20 migration**

```sql
CREATE TABLE IF NOT EXISTS column_profiles (
    id BIGSERIAL PRIMARY KEY,
    table_fqn VARCHAR(512) NOT NULL,
    column_name VARCHAR(256) NOT NULL,
    profiled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    null_count BIGINT,
    null_rate DOUBLE PRECISION,
    distinct_count BIGINT,
    distinct_rate DOUBLE PRECISION,
    empty_string_count BIGINT,
    min_value TEXT,
    max_value TEXT,
    avg_length DOUBLE PRECISION,
    top_values JSONB,
    detected_class VARCHAR(32),
    quality_score DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_column_profiles_table
    ON column_profiles(table_fqn, column_name, profiled_at DESC);
```

- [ ] **Step 2: ColumnProfileEntity.java**

```java
@Entity
@Table(name = "column_profiles")
public class ColumnProfileEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "table_fqn") private String tableFQN;
    @Column(name = "column_name") private String columnName;
    @Column(name = "profiled_at") private Instant profiledAt = Instant.now();
    @Column(name = "null_count") private Long nullCount;
    @Column(name = "null_rate") private Double nullRate;
    // ... 全部字段 + getters/setters
}
```

- [ ] **Step 3: ColumnDefParser.java**

```java
package com.heirloom.metadata.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.metadata.ColumnDef;
import java.util.*;

public class ColumnDefParser {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static List<ColumnDef> parse(String columnsJson) {
        try {
            ColumnDef[] defs = mapper.readValue(columnsJson, ColumnDef[].class);
            return List.of(defs);
        } catch (Exception e) {
            // 旧格式兼容：List<Map<String,Object>>
            return parseLegacy(columnsJson);
        }
    }

    private static List<ColumnDef> parseLegacy(String json) {
        List<Map<String,Object>> legacy = mapper.readValue(json, List.class);
        List<ColumnDef> result = new ArrayList<>();
        int i = 0;
        for (Map<String,Object> col : legacy) {
            result.add(new ColumnDef(
                (String) col.getOrDefault("name", "col_" + i),
                (String) col.getOrDefault("dataType", "varchar"),
                col.containsKey("dataLength") ? ((Number) col.get("dataLength")).intValue() : null,
                null, null,
                false, null,
                (String) col.getOrDefault("comment", null),
                i,
                List.of()
            ));
            i++;
        }
        return result;
    }
}
```

- [ ] **Step 4: 写 parse 单测**

```java
class ColumnDefParserTest {
    @Test
    void shouldParseNewFormat() {
        var json = "[{\"name\":\"id\",\"dataType\":\"integer\",\"nullable\":false,\"ordinalPosition\":0,\"tags\":[]}]";
        var defs = ColumnDefParser.parse(json);
        assertEquals(1, defs.size());
        assertEquals("id", defs.get(0).name());
    }

    @Test
    void shouldParseLegacyFormat() {
        var json = "[{\"name\":\"legacy_col\",\"type\":\"text\"}]";
        var defs = ColumnDefParser.parse(json);
        assertEquals(1, defs.size());
        assertEquals("legacy_col", defs.get(0).name());
    }
}
```

- [ ] **Step 5: 验证 + Commit**

```bash
mvn test -pl heirloom-server -Dtest="ColumnDefParserTest"
git add -A
git commit -m "feat: add ColumnDefParser (legacy compat), ColumnProfileEntity, Flyway V20"
```

---

### Task 1.7: Phase 1 验证

- [ ] **Step 1: 全量测试**

```bash
mvn clean test
```

- [ ] **Step 2: Demo 验证**

```bash
# 启动应用，测试：
# POST /v1/classifications → create "PII"
# POST /v1/tags → create "PII.Email" under "PII"
# GET /v1/tables/{fqn} → 响应含新字段 tags, domainFQN, lifecycle...
```

- [ ] **Step 3: Commit**

```bash
git commit -m "chore: complete Phase 1 — metadata entities with Classification/Tag/Domain, ColumnDefParser, Flyway V17-V20"
```

**Phase 1 退出标准达成：** Classification → Tag → Table（column tags）→ Domain 四表可用。TableEntity 新字段读写通过。ColumnDef 新旧格式兼容解析通过。

---

## Phase 2: Profiling 引擎

### Task 2.0: heirloom-core 定义 Profiling 接口

**Files:**
- Create: `heirloom-core/.../profiling/ProfilingService.java`
- Create: `heirloom-core/.../profiling/ProfileReport.java`
- Create: `heirloom-core/.../profiling/ColumnProfileResult.java`
- Create: `heirloom-core/.../profiling/ValueFrequency.java`
- Create: `heirloom-core/.../profiling/DataClass.java`
- Create: `heirloom-core/.../profiling/SamplingStrategy.java`

- [ ] **Step 1: 全部创建（代码见 Spec 4.1 + 4.2 + 4.3）**

- [ ] **Step 2: 编译 heirloom-core**

```bash
mvn compile -pl heirloom-core
```

- [ ] **Step 3: Commit**

```bash
git add heirloom-core/
git commit -m "feat: define profiling interfaces in heirloom-core"
```

---

### Task 2.1: 实现 PostgresSamplingStrategy

**Files:**
- Create: `heirloom-server/.../profiling/service/PostgresSamplingStrategy.java`

- [ ] **Step 1: 实现**

```java
package com.heirloom.profiling.service;

import com.heirloom.core.profiling.SamplingStrategy;

public class PostgresSamplingStrategy implements SamplingStrategy {
    private static final long SAMPLE_THRESHOLD = 1_000_000L;
    private final double sampleRate;

    public PostgresSamplingStrategy(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    @Override
    public String apply(String tableSql, long estimatedRows) {
        if (estimatedRows > SAMPLE_THRESHOLD) {
            return String.format("(SELECT * FROM %s TABLESAMPLE BERNOULLI(%.0f)) AS _sample",
                tableSql, sampleRate * 100);
        }
        return tableSql;
    }
}
```

- [ ] **Step 2: 单测**

```java
class PostgresSamplingStrategyTest {
    @Test
    void shouldSampleLargeTable() {
        var s = new PostgresSamplingStrategy(0.1);
        var sql = s.apply("public.users", 5_000_000L);
        assertTrue(sql.contains("TABLESAMPLE BERNOULLI(10)"));
    }

    @Test
    void shouldNotSampleSmallTable() {
        var s = new PostgresSamplingStrategy(0.1);
        var sql = s.apply("public.config", 100L);
        assertEquals("public.config", sql);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: implement PostgresSamplingStrategy"
```

---

### Task 2.2: 实现 ProfilingServiceImpl (JDBC)

**Files:**
- Create: `heirloom-server/.../profiling/service/ProfilingServiceImpl.java`
- Test: `heirloom-server/src/test/.../profiling/service/ProfilingServiceImplTest.java`

这是最大的单个实现文件（~200 行）。核心逻辑：

```java
@Service
public class ProfilingServiceImpl implements ProfilingService {

    private final DataSource dataSource;
    private final SamplingStrategy sampling;
    private final ColumnProfileRepository profileRepo;

    public ProfilingServiceImpl(DataSource dataSource, ColumnProfileRepository profileRepo) {
        this.dataSource = dataSource;
        this.profileRepo = profileRepo;
        this.sampling = new PostgresSamplingStrategy(0.1);
    }

    @Override
    public ProfileReport profile(String tableFQN) {
        long start = System.currentTimeMillis();
        try (var conn = dataSource.getConnection()) {
            String sampledTable = sampling.apply(tableFQN, estimateRows(conn, tableFQN));
            long rowCount = countRows(conn, sampledTable);
            List<ColumnProfileResult> columns = profileColumns(conn, sampledTable, tableFQN);

            double overallScore = columns.stream()
                .mapToDouble(ColumnProfileResult::qualityScore)
                .average().orElse(0.0);

            // 持久化
            for (var col : columns) {
                var entity = new ColumnProfileEntity();
                entity.setTableFQN(tableFQN);
                entity.setColumnName(col.columnName());
                // ... 映射全部字段
                profileRepo.save(entity);
            }

            long duration = System.currentTimeMillis() - start;
            return new ProfileReport(tableFQN, rowCount, columns.size(),
                Instant.now(), duration, columns, overallScore);
        } catch (SQLException e) {
            throw new RuntimeException("Profiling failed for " + tableFQN, e);
        }
    }

    // ... 私有方法: estimateRows, countRows, profileColumn, 等
}
```

- [ ] **Step 1-8: TDD 逐方法实现**

每个指标方法先写测试（使用 H2 @DataJpaTest + Testcontainers），再实现。

指标实现（7 个 SQL）：

```java
private ColumnProfileResult profileColumn(Connection conn, String table, String col, String dataType) {
    // nullCount / nullRate
    long nullCount = queryLong(conn, String.format("SELECT COUNT(*) FROM %s WHERE \"%s\" IS NULL", table, col));
    long total = queryLong(conn, String.format("SELECT COUNT(*) FROM %s", table));
    double nullRate = total > 0 ? (double) nullCount / total : 0;

    // distinctCount / distinctRate
    long distinctCount = queryLong(conn, String.format("SELECT COUNT(DISTINCT \"%s\") FROM %s", col, table));
    double distinctRate = total > 0 ? (double) distinctCount / total : 0;

    // emptyStringCount
    long emptyCount = queryLong(conn, String.format("SELECT COUNT(*) FROM %s WHERE \"%s\" = ''", table, col));

    // minValue / maxValue
    String minValue = queryString(conn, String.format("SELECT MIN(\"%s\"::text) FROM %s", col, table));
    String maxValue = queryString(conn, String.format("SELECT MAX(\"%s\"::text) FROM %s", col, table));

    // avgLength
    Double avgLength = queryDouble(conn, String.format("SELECT AVG(LENGTH(\"%s\"::text)) FROM %s", col, table));

    // topValues
    List<ValueFrequency> topValues = queryTopValues(conn, table, col, 10);

    // DataClass inference
    DataClass detected = DataClassInferrer.infer(col, dataType, distinctCount, distinctRate,
        total, topValues, minValue, maxValue, avgLength);

    // quality score
    double score = QualityScorer.score(nullRate, distinctRate, dataType, topValues, avgLength);

    return new ColumnProfileResult(col, dataType, nullCount, nullRate, distinctCount,
        distinctRate, emptyCount, minValue, maxValue, avgLength, topValues, detected, score);
}
```

- [ ] **Step 9: 质量评分实现**

```java
// heirloom-server/.../profiling/service/QualityScorer.java
public class QualityScorer {
    static double score(double nullRate, double distinctRate, String dataType,
                        List<ValueFrequency> topValues, Double avgLength) {
        double score = 0;
        score += (1.0 - nullRate) * 0.30;  // 空值率 30%
        score += distinctRate * 0.20;       // 唯一值率 20%
        score += typeConsistency(dataType) * 0.20;  // 类型一致性 20%
        score += enumStability(topValues) * 0.15;   // 枚举稳定性 15%
        score += lengthConsistency(avgLength) * 0.15; // 长度一致性 15%
        return Math.min(1.0, Math.max(0.0, score));
    }
    // ...
}
```

- [ ] **Step 10: DataClassInferrer**

```java
// heirloom-server/.../profiling/service/DataClassInferrer.java
public class DataClassInferrer {
    public static DataClass infer(String colName, String dataType,
                                   long distinctCount, double distinctRate, long total,
                                   List<ValueFrequency> topValues,
                                   String minValue, String maxValue, Double avgLength) {
        // Priority 1: BOOLEAN_LIKE
        if (isBooleanLike(topValues)) return DataClass.BOOLEAN_LIKE;
        // Priority 2: EMAIL
        if (colName.toLowerCase().contains("email") && avgLength != null && avgLength > 5) return DataClass.EMAIL;
        // Priority 3: PHONE
        if (colName.toLowerCase().contains("phone") || colName.toLowerCase().contains("mobile")) return DataClass.PHONE;
        // Priority 4: ENUM
        if (distinctRate < 0.05 && distinctCount <= 20) return DataClass.ENUM;
        // Priority 5: TEMPORAL
        if (isTemporal(minValue) || isTemporal(maxValue)) return DataClass.TEMPORAL;
        // Priority 6: NUMERIC
        if (isNumeric(minValue) || isNumeric(maxValue)) return DataClass.NUMERIC;
        // Priority 7: UNKNOWN
        return DataClass.UNKNOWN;
    }
    // ...
}
```

- [ ] **Step 11: 添加 Testcontainers 依赖（若尚未存在）**

检查 `heirloom-server/pom.xml` 中是否已有 Testcontainers 依赖。若没有，添加：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.20.6</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>1.20.6</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.20.6</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 12: 集成测试 — Testcontainers**

```java
@Testcontainers
class ProfilingServiceImplIntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test
    void shouldProfileTable() {
        // 建表、插入测试数据
        // 调用 profilingService.profile("public.test_users")
        // 验证 nullCount, distinctCount, topValues, DataClass 推断
    }
}
```

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: implement ProfilingServiceImpl with JDBC + DataClass inference + quality scoring"
```

---

### Task 2.2b: column_profiles 历史保留清理

**Files:**
- Create: `heirloom-server/.../profiling/service/ColumnProfileCleanupService.java`

- [ ] **Step 1: 实现**

```java
@Service
public class ColumnProfileCleanupService {
    private final ColumnProfileJpaRepository jpa;
    private static final int MAX_HISTORY = 5;

    public void cleanup(String tableFQN, String columnName) {
        var all = jpa.findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, columnName);
        if (all.size() > MAX_HISTORY) {
            var toDelete = all.subList(MAX_HISTORY, all.size());
            jpa.deleteAll(toDelete);
        }
    }
}
```

- [ ] **Step 2: 在 ProfilingServiceImpl 中调用**

每次写入新 ColumnProfile 后调用 `cleanupService.cleanup(tableFQN, colName)`。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add column_profiles retention cleanup (keep last 5)"
```

### Task 2.3: 增强 TableProfileEntity + Flyway V21

**Files:**
- Create: `heirloom-server/src/main/resources/db/migration/V21__enhance_table_profiles.sql`
- Modify: `heirloom-server/.../metadata/domain/TableProfileEntity.java`

- [ ] **Step 1: V21 SQL**

```sql
ALTER TABLE table_profiles
  ADD COLUMN IF NOT EXISTS profiled_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS profiling_duration_ms BIGINT,
  ADD COLUMN IF NOT EXISTS null_count BIGINT,
  ADD COLUMN IF NOT EXISTS distinct_count BIGINT,
  ADD COLUMN IF NOT EXISTS duplicate_row_count BIGINT;
```

- [ ] **Step 2: TableProfileEntity 加对应字段 + getters/setters**

- [ ] **Step 3: Migrate + Commit**

```bash
mvn flyway:migrate -pl heirloom-server
git add -A
git commit -m "feat: enhance TableProfileEntity (5 fields), Flyway V21"
```

---

### Task 2.4: DiscoveryService 插入 Profiling 步骤

**Files:**
- Modify: `heirloom-server/.../discovery/service/DiscoveryService.java`

- [ ] **Step 1: 在 runDiscovery() 方法的 Schema 提取之后、InferencePipeline 之前插入 Profiling**

```java
// DiscoveryService.runDiscovery() 中：
RawSchema rawSchema = runner.extract(config);

// --- 插入 Profiling ---
ProfileReport profile = null;
for (RawTable table : rawSchema.tables()) {
    String tableFQN = buildTableFQN(source, table);
    profile = profilingService.profile(tableFQN);
}
// --- 结束 ---

InferenceContext ctx = new InferenceContext(rawSchema, profile, null, null, null);
List<ResourceTypeProposal> proposals = inferencePipeline.infer(ctx);
```

- [ ] **Step 2: 集成测试验证联动**

```bash
mvn test -pl heirloom-server -Dtest="DiscoveryServiceIntegrationTest"
```

- [ ] **Step 3: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/discovery/service/DiscoveryService.java
git commit -m "feat: integrate Profiling into DiscoveryService pipeline"
```

**Phase 2 退出标准达成：** `POST /v1/profiling/tables/{fqn}` 返回含 DataClass 推断的列级报告。Discovery 流程含 Profiling 步骤。

---

## Phase 3: InferencePipeline 升级 + Alignment

### Task 3.0: heirloom-core 定义 Alignment 接口

**Files:**
- Create: `heirloom-core/.../alignment/` — 8 个文件

（代码见 Spec 5.3，已在上方文件结构总览中列出）

- [ ] **Step 1: 全部创建并编译 heirloom-core**

```bash
mvn compile -pl heirloom-core
git add heirloom-core/
git commit -m "feat: define alignment interfaces in heirloom-core"
```

---

### Task 3.1: InferenceContext + InferenceRule 签名升级

> **注**: Spec 5.1 将 InferenceContext/InferenceRule/InferencePipeline 标注为 `heirloom-core (after)`，但 InferenceRule 和 InferencePipeline 的现有实现依赖 `heirloom-server` 中的具体类（如 `ResourceTypeProposal`）。为避免模块化范围扩大，本计划将三者留在 `heirloom-server`，只提取 `heirloom-core` 需要的纯接口（`ProfilingService`、`AlignmentService` 已在 Phase 2-3 提取完成）。未来如果 InferencePipeline 需要作为 core 能力被外部复用，再行迁移。

**Files:**
- Create: `heirloom-server/.../discovery/inference/InferenceContext.java`
- Modify: `heirloom-server/.../discovery/inference/InferenceRule.java` — 改签名
- Modify: `heirloom-server/.../discovery/inference/InferencePipeline.java` — 改签名
- Modify: 6 条 InferenceRule 实现 — 改签名 + 加增强分支

- [ ] **Step 1: 创建 InferenceContext.java**

```java
package com.heirloom.discovery.inference;

import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.profiling.ProfileReport;
import com.heirloom.core.alignment.AlignmentMap;
import java.util.List;

public record InferenceContext(
    RawSchema rawSchema,
    ProfileReport profile,      // nullable
    AlignmentMap alignment,     // nullable
    List<String> tableTags,     // nullable
    String domainFQN            // nullable
) {}
```

- [ ] **Step 2: 修改 InferenceRule.java 签名**

```java
// Before:
public interface InferenceRule {
    List<ResourceTypeProposal> infer(RawSchema schema);
}

// After:
package com.heirloom.discovery.inference;

import com.heirloom.core.discovery.model.RawSchema;
import java.util.List;

public interface InferenceRule {
    List<ResourceTypeProposal> infer(InferenceContext ctx);
}
```

- [ ] **Step 3: 修改 InferencePipeline.java 签名**

```java
// Before:
public interface InferencePipeline {
    List<ResourceTypeProposal> infer(RawSchema rawSchema);
}

// After:
public interface InferencePipeline {
    List<ResourceTypeProposal> infer(InferenceContext ctx);
}
```

修改 `InferencePipeline` 实现类（如果存在）中 `infer()` 方法调用。

- [ ] **Step 4: 修改 6 条规则的签名**

每个 `infer(RawSchema schema)` → `infer(InferenceContext ctx)`，内部用 `ctx.rawSchema()` 访问 RawSchema。

```bash
# 6 个文件在 heirloom-server/src/main/java/com/heirloom/discovery/inference/rules/
# TypeNameInference.java, FieldMapperInference.java, RelationshipInference.java,
# DescriptionInference.java, AbilityInference.java, StateMachineInference.java
```

- [ ] **Step 5: 加 Profiling 增强分支**

以 `FieldMapperInference.java` 为例：

```java
@Override
public List<ResourceTypeProposal> infer(InferenceContext ctx) {
    RawSchema schema = ctx.rawSchema();
    // ... 现有逻辑 ...

    // Profiling 增强（new）
    if (ctx.profile() != null) {
        for (ColumnProfileResult col : ctx.profile().columns()) {
            // DataClass=EMAIL → 字段标注
            // nullRate>0.5 → 标记可空
        }
    }
    // ...
}
```

同样处理 `AbilityInference`（rowCount 判断）和 `StateMachineInference`（topValues 枚举状态）。

- [ ] **Step 6: 编译 + 测试**

```bash
mvn test -pl heirloom-server
```

- [ ] **Step 7: 更新 InferencePipeline 实现中的规则链顺序**

按 Spec 5.4 新顺序排列规则：
`TypeNameInference → FieldMapperInference → RelationshipInference → DescriptionInference → AlignmentInference → AbilityInference → StateMachineInference`

找到 InferencePipeline 的实现类（如 `InferencePipelineImpl` 或 `DiscoveryService` 中直接调用的位置），重新排列规则列表顺序。Align 移到了 Ability 之前，确保语义对齐结果影响 Ability 推断。

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: upgrade InferenceRule/Pipeline to InferenceContext, add profiling-enhanced branches to 6 rules"
```

---

### Task 3.2: 实现 AlignmentInference

**Files:**
- Create: `heirloom-server/.../discovery/inference/rules/AlignmentInference.java`
- Create: `heirloom-server/.../alignment/service/AlignmentServiceImpl.java`

- [ ] **Step 1: AlignmentServiceImpl.java**

```java
@Service
public class AlignmentServiceImpl implements AlignmentService {

    private final TypeRepository typeRepository;
    private final ResourceJpaRepository resourceJpa;

    public AlignmentServiceImpl(TypeRepository typeRepository, ResourceJpaRepository resourceJpa) {
        this.typeRepository = typeRepository;
        this.resourceJpa = resourceJpa;
    }

    @Override
    public AlignmentMap align(AlignmentRequest request) {
        List<FieldAlignment> alignments = new ArrayList<>();
        // 从 request.tableFQN 获取 columns
        // 对每个 column 尝试 5 种对齐信号：
        //   NAME_SIMILARITY: Levenshtein 距离与已知 ResourceType.field.name
        //   TYPE_MATCH: 列类型与 FieldType 兼容
        //   PROFILE_MATCH: DataClass → 已知字段
        //   TAG_MATCH: ColumnDef.tags → ResourceType.field.tags
        //   VALUE_OVERLAP: skip (需要 Resource 实例数据，Phase 3 MVP 不做)
        // ...
        return new AlignmentMap(request.tableFQN(), alignments, unmapped, suggestions);
    }

    // 名称相似度：Levenshtein 距离 < 3
    private boolean isNameSimilar(String colName, String fieldName) {
        return editDistance(colName.toLowerCase(), fieldName.toLowerCase()) < 3;
    }
}
```

- [ ] **Step 2: AlignmentInference.java**

```java
package com.heirloom.discovery.inference.rules;

import com.heirloom.core.alignment.AlignmentService;
import com.heirloom.core.alignment.AlignmentRequest;
import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import java.util.*;

public class AlignmentInference implements InferenceRule {

    private final AlignmentService alignmentService;

    public AlignmentInference(AlignmentService alignmentService) {
        this.alignmentService = alignmentService;
    }

    @Override
    public List<ResourceTypeProposal> infer(InferenceContext ctx) {
        if (ctx.rawSchema() == null) return List.of();

        var request = new AlignmentRequest(
            buildTableFQN(ctx),  // 从 RawSchema 推断
            null,                // 不限 target ontology
            false                // MVP 不建议全新类型
        );
        var alignment = alignmentService.align(request);

        // 高置信度对齐转为 ResourceTypeProposal 字段建议
        List<ResourceTypeProposal> proposals = new ArrayList<>();
        // ...
        return proposals;
    }
}
```

- [ ] **Step 3: 注册到 InferencePipeline**

在 InferencePipeline 实现类中注册为第 7 条规则。

- [ ] **Step 4: 单测**

```java
class AlignmentInferenceTest {
    @Test
    void shouldAlignEmailColumnByName() {
        var mockService = mock(AlignmentService.class);
        var rule = new AlignmentInference(mockService);
        // setup mock alignment result
        // verify proposals contain alignment suggestions
    }
}
```

- [ ] **Step 5: 编译 + 测试 + Commit**

```bash
mvn test -pl heirloom-server
git add -A
git commit -m "feat: implement AlignmentServiceImpl + AlignmentInference (7th rule)"
```

---

### Task 3.3: 端到端集成验证

- [ ] **Step 1: DiscoveryService 更新为传入完整 InferenceContext**

```java
// DiscoveryService.runDiscovery():
InferenceContext ctx = new InferenceContext(rawSchema, profile, alignment, tableTags, domainFQN);
List<ResourceTypeProposal> proposals = inferencePipeline.infer(ctx);
```

- [ ] **Step 2: 写集成测试**

```java
@Testcontainers
class DiscoveryWithProfilingAndAlignmentTest {
    @Test
    void shouldRunFullPipelineIncludingProfilingAndAlignment() {
        // 1. 创建 DiscoverySource（PG 连接）
        // 2. 创建测试表 + 数据
        // 3. 运行 Discovery
        // 4. 验证产出含 Profiling 数据 + Alignment 建议
    }
}
```

- [ ] **Step 3: 全量测试**

```bash
mvn clean test
```

预期：所有测试 PASS（现有 192 + 新增）。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: end-to-end Discovery → Profiling → Align → Infer pipeline, integration test"
```

**Phase 3 退出标准达成：** 端到端管线产出含 DataClass 推断 + Alignment 建议的 ResourceType Proposal。

---

## Phase 4: API 层

### Task 4.0: ClassificationResource + TagResource

**Files:**
- Create: `heirloom-server/.../metadata/web/ClassificationResource.java`
- Create: `heirloom-server/.../metadata/web/TagResource.java`

- [ ] **Step 1: ClassificationResource.java**

```java
@RestController
@RequestMapping("/v1/classifications")
public class ClassificationResource {
    private final ClassificationRepository repo;

    public ClassificationResource(ClassificationRepository repo) { this.repo = repo; }

    @GetMapping
    public List<ClassificationEntity> list() { return repo.listAll(); }

    @GetMapping("/{fqn}")
    public ClassificationEntity get(@PathVariable String fqn) { return repo.findByFQN(fqn); }

    @PostMapping
    public ClassificationEntity create(@RequestBody ClassificationEntity entity) {
        return repo.create(entity);
    }

    @DeleteMapping("/{fqn}")
    public void delete(@PathVariable String fqn) { repo.deleteByFQN(fqn); }
}
```

- [ ] **Step 2: TagResource.java** — 同上模式，加 `PUT /v1/tables/{fqn}/columns/{name}/tags` 给列打标

- [ ] **Step 3: API 测试**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClassificationResourceTest {
    @Test
    void shouldCreateAndGetClassification() {
        // POST /v1/classifications
        // GET /v1/classifications/{fqn}
        // assert 201 + 200
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add ClassificationResource + TagResource REST endpoints"
```

---

### Task 4.1: ColumnResource + ProfilingResource + AlignmentResource

- [ ] **Step 1-3: 创建三个 Controller**（代码见下方）

ColumnResource:
```java
@RestController
@RequestMapping("/v1/tables/{tableFQN}/columns")
public class ColumnResource {
    @GetMapping
    public List<ColumnDef> list(@PathVariable String tableFQN, @RequestParam(required = false) String tag) {
        // 从 TableEntity.columnsJson 解析 ColumnDef 列表
        // 可选按 tag FQN 过滤
    }
}
```

ProfilingResource:
```java
@RestController
@RequestMapping("/v1/profiling")
public class ProfilingResource {
    @PostMapping("/tables/{tableFQN}")
    public ResponseEntity<?> trigger(@PathVariable String tableFQN) {
        // 异步执行 profiling，返回 202 + Location header
    }

    @GetMapping("/tables/{tableFQN}")
    public ProfileReport getLatest(@PathVariable String tableFQN) {
        return profilingService.profile(tableFQN);  // 同步版本
    }

    @GetMapping("/tables/{tableFQN}/history")
    public List<ColumnProfileEntity> history(@PathVariable String tableFQN) {
        return columnProfileRepository.findByTableFQNOrderByProfiledAtDesc(tableFQN);
    }
}
```

AlignmentResource:
```java
@RestController
@RequestMapping("/v1/alignment")
public class AlignmentResource {
    @PostMapping("/tables/{tableFQN}")
    public AlignmentMap trigger(@PathVariable String tableFQN) {
        return alignmentService.align(new AlignmentRequest(tableFQN, null, false));
    }

    @GetMapping("/tables/{tableFQN}")
    public AlignmentMap get(@PathVariable String tableFQN) {
        return alignmentService.align(new AlignmentRequest(tableFQN, null, false));
    }
}
```

- [ ] **Step 4: API 集成测试**

```bash
mvn test -pl heirloom-server -Dtest="*ResourceTest"
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add ColumnResource, ProfilingResource, AlignmentResource REST endpoints"
```

---

### Task 4.2: 增强现有端点

- [ ] **Step 1: DiscoveryResource — 加 profile 参数**

```java
@PostMapping("/sources/{sourceFQN}/run")
public DiscoveryReport run(@PathVariable String sourceFQN,
                           @RequestParam(defaultValue = "false") boolean profile) {
    return discoveryService.runDiscovery(sourceFQN, profile);
}
```

- [ ] **Step 2: TableResource — 响应加新字段**

如果 `TableResource` 不存在，创建它。响应包含 `tags, domainFQN, constraints, sourceHash, lifecycle, certification`。

- [ ] **Step 3: 验证现有端点不破坏**

```bash
mvn test -pl heirloom-server
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: enhance DiscoveryResource (profile param), add TableResource with new fields"
```

**Phase 4 退出标准达成：** 全部 API 可用，全量测试通过。

---

## 最终验证

- [ ] **全量测试**

```bash
mvn clean test
```

预期：所有模块编译通过，所有测试 PASS（估计 230-250 个测试，含新增 40-60 个）。

- [ ] **mvn verify（含集成测试）**

```bash
mvn clean verify
```

- [ ] **代码风格检查**

```bash
# 如果有 checkstyle plugin：
mvn checkstyle:check
```

---

## 总结

| Phase | Tasks | 新建文件 | 修改文件 | 预计测试数 |
|-------|-------|---------|---------|----------|
| 0 | 7 | ~40 | ~30 (import 路径) | 192 (回归) |
| 1 | 8 | ~16 | 2 | +15 (新增) |
| 2 | 6 | ~12 | 2 | +15 (新增) |
| 3 | 4 | ~11 | 9 | +10 (新增) |
| 4 | 3 | ~8 | 2 | +10 (新增) |
| **合计** | **28** | **~87** | **~45** | **~242** |
