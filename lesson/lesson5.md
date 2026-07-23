# 第 5 课：角色权限与多租户数据隔离

> 课程重点内容总结

---

## 一、RBAC 角色权限控制

### 1.1 角色定义

**文件**：`model/User.java`

```java
public enum Role {
    USER,   // 普通用户
    ADMIN   // 管理员
}
```

### 1.2 接口权限划分

**文件**：`config/SecurityConfig.java`

| 权限类型 | 接口范围 | 说明 |
|----------|----------|------|
| **公开** | `/api/v1/users/login`, `/api/v1/users/register` | 无需认证 |
| **USER+ADMIN** | `/api/v1/upload/**`, `/api/search/**` | 普通功能 |
| **仅 ADMIN** | `/api/v1/admin/**` | 管理功能 |

### 1.3 管理员专属接口

**文件**：`controller/AdminController.java`

| 接口 | 说明 |
|------|------|
| `GET /admin/users` | 获取所有用户列表 |
| `POST /admin/knowledge/add` | 添加知识库文档 |
| `GET /admin/system/status` | 查看系统状态 |
| `POST /admin/users/create-admin` | 创建管理员账户 |
| `POST /admin/org-tags` | 创建组织标签 |
| `POST /admin/clear-all-data` | 清空所有数据 |

### 1.4 第一个注册用户自动成为管理员

**文件**：`service/UserService.java`

```java
public void registerUser(String username, String password) {
    boolean hasAdmin = userRepository.findAll().stream()
            .anyMatch(user -> User.Role.ADMIN.equals(user.getRole()));

    if (!hasAdmin) {
        user.setRole(User.Role.ADMIN);  // 第一个用户 → ADMIN
    } else {
        user.setRole(User.Role.USER);   // 后续用户 → USER
    }
}
```

### 1.5 Service 层二次验证

```java
// 除了 SecurityConfig 的接口拦截，Service 层还会再次验证
if (creator.getRole() != User.Role.ADMIN) {
    throw new CustomException("Only administrators can create admin accounts",
                               HttpStatus.FORBIDDEN);
}
```

---

## 二、RBAC 与 ABAC 对比

| 维度 | RBAC（角色控制） | ABAC（组织标签） |
|------|----------------|----------------|
| **控制粒度** | 功能级（能做什么） | 数据级（能看到什么） |
| **扩展性** | 部门增加需新增角色 | 新增标签即可 |
| **数据隔离** | 无法实现 | 文档级过滤 |
| **应用场景** | 接口访问控制 | 文档可见性控制 |

**PaiSmart 的设计**：
```
功能权限（RBAC） + 数据权限（ABAC） = 完整权限体系
```

---

## 三、组织标签数据模型

### 3.1 组织标签实体

**文件**：`model/OrganizationTag.java`

```java
@Table(name = "organization_tags")
public class OrganizationTag {
    private String tagId;       // 标签唯一标识
    private String name;        // 标签名称（如"研发部"）
    private String parentTag;   // 父标签 ID（支持层级）
    private User createdBy;     // 创建者（仅管理员可创建）
}
```

**层级结构示例**：
```
Company（根）
├── R&D
│   ├── Backend
│   └── Frontend
├── Sales
└── Finance
```

### 3.2 用户组织标签

**文件**：`model/User.java`

```java
private String orgTags;    // "R&D,Backend" 逗号分隔，多个标签
private String primaryOrg; // "Backend" 主组织（上传文件时默认归属）
```

### 3.3 资源组织标签

**文件**：`model/FileUpload.java` / `model/DocumentVector.java`

```java
private String userId;     // 上传者 ID
private String orgTag;     // 文档所属组织
private boolean isPublic;  // 是否公开
```

---

## 四、文档访问权限规则

**文件**：`config/OrgTagAuthorizationFilter.java`

```
判断顺序（满足任一即允许访问）：

1. isPublic = true           → 公开文档，所有人可访问
2. userId = 当前用户        → 自己上传的文档
3. role = ADMIN             → 管理员可访问所有文档
4. orgTag.startsWith("PRIVATE_") → 私人空间，仅拥有者可访问
5. userOrgTags.contains(orgTag)  → 同一组织的用户
```

---

## 五、层级标签继承

**文件**：`service/OrgTagCacheService.java`

用户属于 `Backend` 标签，系统会**递归向上**收集所有父标签：

```java
private void collectParentTags(String tagId, Set<String> result) {
    OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
    if (tag != null && tag.getParentTag() != null) {
        result.add(tag.getParentTag());
        collectParentTags(tag.getParentTag(), result);  // 递归
    }
}
```

**效果**：
```
用户标签：["Backend"]
有效标签：["Backend", "R&D", "Company", "DEFAULT"]
→ 自动继承父级权限
```

**Redis 缓存**：有效标签缓存 24 小时，避免每次重复查询

```
Redis Key 设计：
user:org_tags:{username}          → 用户直接标签（List）
user:primary_org:{username}       → 主组织（String）
user:effective_org_tags:{username} → 计算后的有效标签（List）
```

---

## 六、搜索时的动态权限过滤

**文件**：`service/HybridSearchService.java`

```java
public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
    // 1. 获取用户有效标签（含层级继承）
    List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
    // 例：["Backend", "R&D", "DEFAULT"]

    // 2. Elasticsearch 查询 + 权限过滤
    // 关键词匹配 AND (自己的文档 OR 公开文档 OR 组织标签匹配)
    .filter(f -> f.bool(bf -> bf
        .should(s1 -> 自己的文档：userId = userDbId)
        .should(s2 -> 公开文档：public = true)
        .should(s3 -> 组织标签：orgTag IN userEffectiveTags)
    ))
}
```

---

## 七、文件上传时记录组织标签

**文件**：`controller/UploadController.java`

```java
// 上传分片时，如果未指定 orgTag，使用用户的主组织标签
if (orgTag == null || orgTag.isEmpty()) {
    String primaryOrg = userService.getUserPrimaryOrg(userId);
    orgTag = primaryOrg;
}

// 将 orgTag 写入文件记录
uploadService.uploadChunk(fileMd5, chunkIndex, ..., orgTag, isPublic, userId);
```

---

## 八、核心文件

```
model/
├── User.java                      # orgTags, primaryOrg 字段
├── OrganizationTag.java           # 层级标签实体
├── FileUpload.java                # orgTag, isPublic 字段
└── DocumentVector.java            # orgTag, isPublic 字段（搜索用）

config/
└── OrgTagAuthorizationFilter.java # 资源访问权限判断

service/
└── OrgTagCacheService.java        # 有效标签计算与缓存

service/
└── HybridSearchService.java       # 搜索时的动态权限过滤
```

---

## 九、实践场景

| 场景 | 操作 |
|------|------|
| 新员工入职 | 分配 orgTags，自动继承上级标签权限 |
| 员工转岗 | 更新 orgTags，旧权限自动失效 |
| 跨部门协作 | 添加额外标签 或 设置 isPublic=true |
| 个人私密文档 | 上传到 PRIVATE_{userId} 标签 |

---

*课程版本：1.0*
*更新日期：2026-04-06*
