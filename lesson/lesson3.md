# 第 3 课：数据模型与数据库设计

> 课程重点内容总结

---

## 一、数据库表总览

PaiSmart 数据库包含以下核心表：

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| **users** | 用户表 | id, username, password, role, org_tags |
| **organization_tags** | 组织标签表 | tag_id, name, parent_tag, created_by |
| **file_upload** | 文件上传记录 | file_md5, file_name, user_id, org_tag, is_public |
| **document_vectors** | 文档向量表 | file_md5, chunk_id, text_content, user_id, org_tag |
| **chunk_info** | 分块信息表 | file_md5, chunk_index, chunk_md5, storage_path |
| **conversations** | 对话历史表 | user_id, question, answer, timestamp |

---

## 二、核心实体类详解

### 2.1 User（用户实体）

**文件**：`model/User.java`

```java
@Data
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    private Role role;  // "USER" 或 "ADMIN"

    private String orgTags;       // 组织标签（逗号分隔）
    private String primaryOrg;    // 主组织标签

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

**数据库表结构**：
```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
    org_tags VARCHAR(255),
    primary_org VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

---

### 2.2 OrganizationTag（组织标签实体）

**文件**：`model/OrganizationTag.java`

```java
@Data
@Entity
@Table(name = "organization_tags")
public class OrganizationTag {
    @Id
    private String tagId;  // 标签唯一标识

    @Column(nullable = false)
    private String name;  // 标签名称

    @Column(columnDefinition = "TEXT")
    private String description;  // 描述

    @Column(name = "parent_tag")
    private String parentTag;  // 父标签 ID（自引用实现层级）

    @ManyToOne
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;  // 创建者

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

**层级结构示例**：
```
DEFAULT (默认组织)
├── admin (管理员组织)
├── PRIVATE_admin (admin 的私人空间)
├── PRIVATE_user1 (user1 的私人空间)
└── company (公司组织)
    └── department (部门)
```

---

### 2.3 FileUpload（文件上传实体）

**文件**：`model/FileUpload.java`

```java
@Data
@Entity
@Table(name = "file_upload")
public class FileUpload {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;  // 文件 MD5

    private String fileName;  // 文件原始名称
    private long totalSize;   // 文件大小（字节）
    private int status;       // 0-上传中 1-已完成

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;    // 上传用户 ID

    @Column(name = "org_tag")
    private String orgTag;    // 所属组织标签

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;  // 是否公开

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime mergedAt;  // 合并完成时间
}
```

---

### 2.4 Conversation（对话实体）

**文件**：`model/Conversation.java`

```java
@Data
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;  // 关联用户（多对一）

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;  // 用户提问

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;  // 系统回答

    @CreationTimestamp
    private LocalDateTime timestamp;
}
```

**关键点**：
- `@ManyToOne(fetch = FetchType.LAZY)` - 懒加载关联用户
- 索引加速查询：按用户 ID 和时间戳

---

### 2.5 ChunkInfo（分块信息实体）

**文件**：`model/ChunkInfo.java`

```java
@Data
@Entity
@Table(name = "chunk_info")
public class ChunkInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileMd5;     // 文件 MD5
    private int chunkIndex;     // 分块索引（第几块）
    private String chunkMd5;    // 分块 MD5
    private String storagePath; // 存储路径
}
```

---

### 2.6 DocumentVector（文档向量实体）

**文件**：`model/DocumentVector.java`

```java
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;  // 文本内容

    @Column(length = 32)
    private String modelVersion;

    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;  // 权限控制

    @Column(name = "org_tag", length = 50)
    private String orgTag;  // 组织标签

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;  // 公开标记
}
```

---

## 三、JPA 注解详解

### 3.1 基本注解

| 注解 | 作用 | 示例 |
|------|------|------|
| `@Entity` | 标记为 JPA 实体 | `@Entity public class User` |
| `@Table` | 指定表名 | `@Table(name = "users")` |
| `@Id` | 主键 | `@Id private Long id` |
| `@GeneratedValue` | 主键生成策略 | `@GeneratedValue(strategy = IDENTITY)` |
| `@Column` | 列属性 | `@Column(nullable = false, unique = true)` |
| `@Enumerated` | 枚举类型 | `@Enumerated(EnumType.STRING)` |
| `@Lob` | 大文本字段 | `@Lob private String content` |
| `@CreationTimestamp` | 创建时间 | 自动生成 |
| `@UpdateTimestamp` | 更新时间 | 自动更新 |

### 3.2 主键生成策略

```java
// IDENTITY: 数据库自增（MySQL 默认）
@GeneratedValue(strategy = GenerationType.IDENTITY)

// SEQUENCE: 使用序列（Oracle/PostgreSQL）
@GeneratedValue(strategy = GenerationType.SEQUENCE)

// UUID: 使用 UUID
@GeneratedValue(strategy = GenerationType.UUID)
```

### 3.3 关联关系注解

| 注解 | 关系 | 示例 |
|------|------|------|
| `@ManyToOne` | 多对一 | Conversation → User |
| `@OneToMany` | 一对多 | User → Conversations |
| `@OneToOne` | 一对一 | User → UserProfile |
| `@ManyToMany` | 多对多 | User ↔ Roles |
| `@JoinColumn` | 指定外键列 | `@JoinColumn(name = "user_id")` |
| `fetch = FetchType.LAZY` | 懒加载 | 延迟加载关联对象 |

---

## 四、多租户数据隔离设计

### 4.1 租户隔离方案对比

| 方案 | 优点 | 缺点 | PaiSmart 选择 |
|------|------|------|--------------|
| 独立数据库 | 完全隔离 | 成本高 | ❌ |
| 独立 Schema | 逻辑隔离 | 管理复杂 | ❌ |
| **共享表 + 字段隔离** | 成本低、易维护 | 需代码控制 | ✅ |

### 4.2 权限判断逻辑

```java
// 文档可见性规则
1. 公开文档 (isPublic = true): 所有用户可见
2. 私有文档 (isPublic = false):
   - 文档创建者可见
   - 同一组织标签用户可见
   - 子标签用户可见（继承）
```

### 4.3 权限字段设计

```java
// DocumentVector 实体
private String userId;      // 创建者 ID
private String orgTag;      // 所属组织
private boolean isPublic;   // 是否公开
```

---

## 五、数据库关系图 (ER 图)

```
┌─────────────────┐       ┌─────────────────────┐
│     users       │       │ organization_tags   │
├─────────────────┤       ├─────────────────────┤
│ id (PK)         │       │ tag_id (PK)         │
│ username        │       │ name                │
│ password        │       │ description         │
│ role            │       │ parent_tag          │◄──┐
│ org_tags        │       │ created_by (FK)     │───┤
│ primary_org     │       │ created_at          │   │
│ created_at      │       │ updated_at          │   │
│ updated_at      │       └─────────────────────┘   │
└────────┬────────┘              ▲                  │
         │ 1                     │                  │
         ▼ *                     │                  │
┌─────────────────┐              │                  │
│  conversations  │              │                  │
├─────────────────┤              │                  │
│ id (PK)         │              │                  │
│ user_id (FK)    │──────────────┘                  │
│ question        │                                 │
│ answer          │                                 │
│ timestamp       │                                 │
└─────────────────┘                                 │
                                                    │
┌─────────────────┐       ┌─────────────────────────┴────┐
│   file_upload   │       │      document_vectors        │
├─────────────────┤       ├──────────────────────────────┤
│ id (PK)         │       │ vector_id (PK)               │
│ file_md5        │───────┼─► file_md5                   │
│ file_name       │       │ chunk_id                     │
│ total_size      │       │ text_content (Lob)           │
│ status          │       │ model_version                │
│ user_id         │       │ user_id ─────────────────────┼──┐
│ org_tag         │       │ org_tag                      │  │
│ is_public       │       │ is_public                    │  │
│ created_at      │       └──────────────────────────────┘  │
│ merged_at       │                                         │
└─────────────────┘       ┌─────────────────────────────────┘
                          │
┌─────────────────┐       │
│   chunk_info    │       │
├─────────────────┤       │
│ id (PK)         │       │
│ file_md5 ───────┼───────┘
│ chunk_index     │
│ chunk_md5       │
│ storage_path    │
└─────────────────┘
```

---

## 六、Repository 实战

### 6.1 基础 Repository

```java
public interface UserRepository extends JpaRepository<User, Long> {
    // 方法命名约定自动生成 SQL
    Optional<User> findByUsername(String username);
}

// 自动继承的方法：
userRepository.findById(1L);        // SELECT * FROM users WHERE id = 1
userRepository.findAll();           // SELECT * FROM users
userRepository.save(user);          // INSERT/UPDATE
userRepository.delete(user);        // DELETE FROM users
userRepository.count();             // SELECT COUNT(*) FROM users
```

### 6.2 自定义查询方法

```java
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    // 按用户 ID 查询
    List<Conversation> findByUserId(Long userId);

    // 按时间范围查询
    List<Conversation> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    // 按用户和时间排序
    List<Conversation> findByUserIdOrderByTimestampDesc(Long userId);
}
```

### 6.3 使用 @Query 自定义 SQL

```java
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM u WHERE u.orgTags LIKE %:orgTag%")
    List<User> findByOrgTagContaining(String orgTag);

    @Query(value = "SELECT * FROM users WHERE username = ?1", nativeQuery = true)
    Optional<User> findByUsernameNative(String username);
}
```

---

## 七、实践练习

### 练习 1：查看表结构

```bash
docker exec mysql mysql -u root -pPaiSmart2025 -e "
USE PaiSmart;
DESCRIBE users;
DESCRIBE organization_tags;
DESCRIBE conversations;
" 2>&1 | grep -v "Warning"
```

### 练习 2：编写自定义查询

在 `ConversationRepository` 中添加：
```java
// 查询用户最近 10 条对话记录
List<Conversation> findTop10ByUserIdOrderByTimestampDesc(Long userId);
```

### 练习 3：插入测试数据

```bash
docker exec mysql mysql -u root -pPaiSmart2025 -e "
USE PaiSmart;
SELECT * FROM users LIMIT 5;
" 2>&1 | grep -v "Warning"
```

### 练习 4：分析实体关系

画出以下实体之间的关系：
- User → Conversation (一对多)
- User → OrganizationTag (通过 createdBy)
- FileUpload → DocumentVector (通过 fileMd5)

---

## 八、课后总结

### 本课要点

| 知识点 | 关键内容 |
|--------|----------|
| JPA 实体 | @Entity、@Table、@Id、@GeneratedValue |
| 关联关系 | @ManyToOne、@JoinColumn、FetchType.LAZY |
| 多租户 | 共享表 + org_tag 字段隔离 |
| Repository | JpaRepository、方法命名约定、@Query |

### 核心实体类

```
model/
├── User.java              # 用户实体
├── OrganizationTag.java   # 组织标签实体
├── Conversation.java      # 对话实体
├── FileUpload.java        # 文件上传实体
├── DocumentVector.java    # 文档向量实体
└── ChunkInfo.java         # 分块信息实体
```

### 数据库表关系

| 关系 | 类型 | 实现方式 |
|------|------|----------|
| User → Conversation | 一对多 | @ManyToOne + @JoinColumn |
| OrganizationTag 自引用 | 树形结构 | parent_tag 字段 |
| FileUpload → DocumentVector | 一对多 | file_md5 字段关联 |

---

## 九、下节课预告

**第 4 课：用户认证与 JWT 机制**
- Spring Security 配置
- JWT Token 结构与 Claims 设计
- 认证过滤器实现
- Token 刷新机制

---

*课程版本：2.0*
*更新日期：2026-03-17*
