# 第 4 课：用户认证与 JWT 机制

> 课程重点内容总结

---

## 一、Spring Security 配置

### 1.1 SecurityConfig 核心配置

**文件**：`config/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())  // 禁用 CSRF（API 无状态）
            .authorizeHttpRequests(authorize -> authorize
                // 公开接口
                .requestMatchers("/api/v1/users/register", "/api/v1/users/login").permitAll()
                // 需要认证的接口
                .requestMatchers("/api/v1/upload/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // 其他请求需要认证
                .anyRequest().authenticated())
            // 无状态会话策略
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 添加 JWT 过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class)
            .build();
    }
}
```

**关键点**：
- `SessionCreationPolicy.STATELESS` - 不创建 HTTP Session
- 过滤器链：`JwtAuthenticationFilter` → `OrgTagAuthorizationFilter`
- 基于角色的访问控制：`hasRole("ADMIN")`、`hasAnyRole("USER", "ADMIN")`

---

## 二、JWT Token 结构与 Claims 设计

### 2.1 JWT Token 结构

PaiSmart 使用 `io.jsonwebtoken` (JJwt) 库生成和解析 JWT。

**Token 包含的 Claims**：

| Claim | 类型 | 说明 | 示例 |
|-------|------|------|------|
| `sub` | String | 用户名（subject） | `"admin"` |
| `tokenId` | String | Token 唯一标识（Redis 缓存 key） | `"a1b2c3d4..."` |
| `userId` | String | 用户 ID | `"1"` |
| `role` | String | 用户角色 | `"ADMIN"` |
| `orgTags` | String | 组织标签（逗号分隔） | `"DEFAULT,company"` |
| `primaryOrg` | String | 主组织标签 | `"DEFAULT"` |
| `exp` | Date | 过期时间 | `3600000ms (1 小时)` |

### 2.2 Refresh Token 设计

| Claim | 类型 | 说明 |
|-------|------|------|
| `sub` | String | 用户名 |
| `refreshTokenId` | String | Refresh Token 唯一标识 |
| `userId` | String | 用户 ID |
| `type` | String | `"refresh"`（标识 token 类型） |
| `exp` | Date | 过期时间（7 天） |

### 2.3 JWT 工具类核心方法

**文件**：`utils/JwtUtils.java`

```java
@Component
public class JwtUtils {

    // 生成 Access Token
    public String generateToken(String username)

    // 生成 Refresh Token
    public String generateRefreshToken(String username)

    // 验证 Token 有效性（Redis 缓存 + JWT 签名双重验证）
    public boolean validateToken(String token)

    // 提取用户名
    public String extractUsernameFromToken(String token)

    // 提取用户 ID
    public String extractUserIdFromToken(String token)

    // 提取用户角色
    public String extractRoleFromToken(String token)

    // 提取组织标签
    public String extractOrgTagsFromToken(String token)

    // 检查是否应该刷新 token（剩余时间 < 5 分钟）
    public boolean shouldRefreshToken(String token)

    // 检查过期 token 是否可刷新（宽限期 10 分钟内）
    public boolean canRefreshExpiredToken(String token)

    // 刷新 token
    public String refreshToken(String token)

    // 使 token 失效（加入 Redis 黑名单）
    public void invalidateToken(String token)

    // 使用户所有 token 失效（批量登出）
    public void invalidateAllUserTokens(String userId)
}
```

---

## 三、认证过滤器实现

### 3.1 JwtAuthenticationFilter

**文件**：`config/JwtAuthenticationFilter.java`

**过滤器工作流程**：

```
1. 从请求头提取 Token
   Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
       ↓
2. 验证 Token 有效性
   - jwtUtils.validateToken(token)
   - 检查 Redis 缓存 + JWT 签名
       ↓
3. 检查是否需要刷新
   - shouldRefreshToken(token): 剩余时间 < 5 分钟 → 自动刷新
   - canRefreshExpiredToken(token): 过期 < 10 分钟 → 宽限期刷新
       ↓
4. 设置认证信息到 SecurityContext
   UserDetails userDetails = userDetailsService.loadUserByUsername(username)
   SecurityContextHolder.getContext().setAuthentication(authentication)
       ↓
5. 返回新 Token（如果有刷新）
   Response Header: New-Token: eyJhbGciOiJIUzI1NiJ9...
```

**核心代码**：

```java
@Override
protected void doFilterInternal(HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain filterChain) {
    String token = extractToken(request);
    if (token != null) {
        String newToken = null;
        String username = null;

        if (jwtUtils.validateToken(token)) {
            // Token 有效，检查是否需要预刷新
            if (jwtUtils.shouldRefreshToken(token)) {
                newToken = jwtUtils.refreshToken(token);
                response.setHeader("New-Token", newToken);
            }
            username = jwtUtils.extractUsernameFromToken(token);
        } else {
            // Token 无效/过期，检查宽限期
            if (jwtUtils.canRefreshExpiredToken(token)) {
                newToken = jwtUtils.refreshToken(token);
                response.setHeader("New-Token", newToken);
                username = jwtUtils.extractUsernameFromToken(newToken);
            }
        }

        if (username != null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
    filterChain.doFilter(request, response);
}
```

---

## 四、Token 刷新机制

### 4.1 自动刷新策略

| 场景 | 条件 | 行为 |
|------|------|------|
| **预刷新** | Token 有效但剩余时间 < 5 分钟 | 生成新 token，通过 `New-Token` 响应头返回 |
| **宽限期刷新** | Token 过期但 < 10 分钟 | 生成新 token，通过 `New-Token` 响应头返回 |
| **完全过期** | Token 过期 > 10 分钟 | 拒绝请求，前端需使用 Refresh Token 或重新登录 |

### 4.2 Refresh Token 使用流程

```
1. 登录时同时返回 access_token 和 refresh_token
   {
     "token": "eyJhbGciOiJIUzI1NiJ9...",      // 1 小时有效
     "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."  // 7 天有效
   }
       ↓
2. Access Token 过期后，使用 Refresh Token 刷新
   POST /api/v1/users/refresh
   Body: { "refreshToken": "..." }
       ↓
3. 验证 Refresh Token 有效性
   - 验证 JWT 签名
   - 检查 Redis 缓存
   - 验证 type = "refresh"
       ↓
4. 生成新的 Access Token 和 Refresh Token
```

### 4.3 Redis Token 缓存

**文件**：`service/TokenCacheService.java`

**Redis Key 设计**：

| Key 前缀 | 用途 | 示例 |
|---------|------|------|
| `jwt:valid:{tokenId}` | 有效 Token 缓存 | `jwt:valid:a1b2c3d4` |
| `jwt:refresh:{refreshTokenId}` | Refresh Token 缓存 | `jwt:refresh:x9y8z7w6` |
| `jwt:blacklist:{tokenId}` | Token 黑名单 | `jwt:blacklist:a1b2c3d4` |
| `jwt:user:{userId}:tokens` | 用户 Token 集合 | `jwt:user:1:tokens` |

**核心方法**：

```java
@Service
public class TokenCacheService {

    // 缓存 Token 信息
    public void cacheToken(String tokenId, String userId, String username, long expireTimeMs)

    // 验证 Token 有效性
    public boolean isTokenValid(String tokenId)

    // 加入黑名单
    public void blacklistToken(String tokenId, long expireTimeMs)

    // 移除用户所有 Token（批量登出）
    public void removeAllUserTokens(String userId)
}
```

---

## 五、CustomUserDetailsService

**文件**：`service/CustomUserDetailsService.java`

```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                getAuthorities(user.getRole())
        );
    }

    private Collection<? extends GrantedAuthority> getAuthorities(User.Role role) {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
```

**作用**：
- 将项目的 `User` 实体转换为 Spring Security 的 `UserDetails` 接口
- 提供角色权限信息（`ROLE_USER`、`ROLE_ADMIN`）

---

## 六、组织标签授权过滤器

**文件**：`config/OrgTagAuthorizationFilter.java`

**授权规则**：

```
1. 公开资源 (isPublic = true) → 所有用户可访问
2. 私有资源 (isPublic = false)：
   - 资源创建者 → 可访问
   - 管理员 → 可访问
   - 同一组织标签用户 → 可访问
   - 私人空间资源 (PRIVATE_xxx) → 仅创建者可访问
```

**适用接口**：
- 文件上传：`/api/v1/upload/chunk`、`/api/v1/upload/merge`
- 文档管理：`/api/v1/documents/{fileMd5}`
- 知识库：`/api/v1/knowledge/{resourceId}`

---

## 七、登录/登出接口

### 7.1 登录接口

**文件**：`controller/UserController.java`

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody UserRequest request) {
    // 1. 参数校验
    if (request.username() == null || request.username().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Map.of("code", 400, "message", "Username and password cannot be empty"));
    }

    // 2. 调用 Service 验证用户
    String username = userService.authenticateUser(request.username(), request.password());

    // 3. 生成 Token
    String token = jwtUtils.generateToken(username);
    String refreshToken = jwtUtils.generateRefreshToken(username);

    // 4. 返回响应
    return ResponseEntity.ok(Map.of(
        "code", 200,
        "message", "Login successful",
        "data", Map.of(
            "token", token,
            "refreshToken", refreshToken
        )
    ));
}
```

### 7.2 登出接口

```java
@PostMapping("/logout")
public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
    String jwtToken = token.replace("Bearer ", "");

    // 使当前 token 失效（加入 Redis 黑名单）
    jwtUtils.invalidateToken(jwtToken);

    return ResponseEntity.ok(Map.of("code", 200, "message", "Logout successful"));
}
```

### 7.3 批量登出接口

```java
@PostMapping("/logout-all")
public ResponseEntity<?> logoutAll(@RequestHeader("Authorization") String token) {
    String userId = jwtUtils.extractUserIdFromToken(token);

    // 使用户所有 token 失效
    jwtUtils.invalidateAllUserTokens(userId);

    return ResponseEntity.ok(Map.of("code", 200, "message", "Logout from all devices successful"));
}
```

---

## 八、完整认证流程图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           用户认证流程                                   │
└─────────────────────────────────────────────────────────────────────────┘

1. 登录流程
   前端                         后端                         Redis
    │                           │                            │
    │  POST /login              │                            │
    │  {username, password}     │                            │
    │ ─────────────────────────>│                            │
    │                           │ 查询用户                   │
    │                           │───────────> MySQL          │
    │                           │<─────────── User 信息      │
    │                           │ 验证密码                   │
    │                           │ 生成 Token                 │
    │                           │ 缓存到 Redis               │
    │                           │───────────> jwt:valid:*    │
    │  返回 token + refreshToken│                            │
    │<─────────────────────────│                            │
    │                           │                            │

2. 请求认证流程
   前端                         后端                         Redis
    │                           │                            │
    │  GET /api/v1/documents    │                            │
    │  Authorization: Bearer *  │                            │
    │ ─────────────────────────>│                            │
    │                           │ JwtAuthenticationFilter    │
    │                           │ 提取 Token                 │
    │                           │ 验证 Token                 │
    │                           │───────────> jwt:valid:*    │
    │                           │<─────────── 有效           │
    │                           │ 设置 SecurityContext       │
    │                           │ OrgTagAuthorizationFilter  │
    │                           │ 检查资源权限               │
    │                           │ 返回数据                   │
    │  返回数据 + New-Token     │                            │
    │<─────────────────────────│                            │
    │                           │                            │

3. Token 刷新流程
   前端                         后端                         Redis
    │                           │                            │
    │  检测到 New-Token         │                            │
    │  或 Token 过期            │                            │
    │                           │                            │
    │  POST /refresh (可选)     │                            │
    │  {refreshToken}           │                            │
    │ ─────────────────────────>│                            │
    │                           │ 验证 Refresh Token         │
    │                           │───────────> jwt:refresh:*  │
    │                           │<─────────── 有效           │
    │                           │ 生成新 Token               │
    │  返回新 token + refreshToken                          │
    │<─────────────────────────│                            │
    │                           │                            │
```

---

## 九、实践练习

### 练习 1：分析登录日志

启动项目，观察登录时的日志输出：
```bash
# 查看 Token 生成日志
docker logs redis -f | grep "Token generated"
```

### 练习 2：查看 Redis 中的 Token

```bash
docker exec redis redis-cli keys "jwt:*"
docker exec redis redis-cli get "jwt:valid:<tokenId>"
```

### 练习 3：测试 Token 失效

```bash
# 1. 登录获取 token
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# 2. 调用登出接口
curl -X POST http://localhost:8081/api/v1/users/logout \
  -H "Authorization: Bearer $TOKEN"

# 3. 再次使用该 token 请求需要认证的接口
# 预期返回 401 Unauthorized
curl http://localhost:8081/api/v1/documents/uploads \
  -H "Authorization: Bearer $TOKEN"
```

### 练习 4：添加自定义 Claims

在 `JwtUtils.generateToken()` 中添加一个新的 Claim：
```java
claims.put("email", user.getEmail());  // 假设 User 有 email 字段
```

然后添加对应的提取方法：
```java
public String extractEmailFromToken(String token) {
    Claims claims = extractClaimsIgnoreExpiration(token);
    return claims != null ? claims.get("email", String.class) : null;
}
```

---

## 十、课后总结

### 本课要点

| 知识点 | 关键内容 |
|--------|----------|
| Spring Security | SecurityFilterChain、授权规则、无状态会话 |
| JWT 结构 | TokenId、UserId、Role、OrgTags Claims 设计 |
| 认证过滤器 | JwtAuthenticationFilter 解析 Token、设置 SecurityContext |
| Token 刷新 | 预刷新（<5 分钟）、宽限期（过期<10 分钟） |
| Redis 缓存 | Token 状态管理、黑名单机制、批量登出 |
| 组织标签授权 | OrgTagAuthorizationFilter 实现多租户数据隔离 |

### 核心文件

```
config/
├── SecurityConfig.java              # 安全配置主类
├── JwtAuthenticationFilter.java     # JWT 认证过滤器
└── OrgTagAuthorizationFilter.java   # 组织标签授权过滤器

service/
├── CustomUserDetailsService.java    # 加载用户详细信息
└── TokenCacheService.java           # Token Redis 缓存服务

utils/
└── JwtUtils.java                    # JWT 生成与解析工具

controller/
└── UserController.java              # 登录/登出接口
```

### 安全特性

| 特性 | 实现方式 |
|------|----------|
| 无状态认证 | JWT Token + Stateless Session |
| Token 刷新 | 自动预刷新 + 宽限期刷新 |
| Token 失效 | Redis 黑名单机制 |
| 批量登出 | 清除用户所有 Token 缓存 |
| 多租户隔离 | 组织标签授权过滤器 |
| 角色权限 | Spring Security 基于角色的访问控制 |

---

## 十一、下节课预告

**第 5 课：文件上传与分片处理**
- 分片上传接口设计
- Redis Bitmap 进度追踪
- 文件合并与持久化
- MinIO 对象存储集成

---

*课程版本：2.0*
*更新日期：2026-03-19*
