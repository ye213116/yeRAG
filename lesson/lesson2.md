# 第 2 课：后端分层架构设计

> 课程重点内容总结

---

## 一、核心概念

### 1.1 Spring Boot 三层架构

```
Controller 层 → Service 层 → Repository 层 → Database
    ↓             ↓              ↓
  接收请求      业务逻辑        数据访问
  参数校验      事务控制        CRUD 操作
  返回响应      异常处理
```

### 1.2 各层职责

| 层级 | 职责 | 注解 | 示例 |
|------|------|------|------|
| **Controller** | 接收请求、参数校验、返回响应 | `@RestController` | `UserController` |
| **Service** | 业务逻辑、事务控制 | `@Service` | `UserService` |
| **Repository** | 数据访问、CRUD | `@Repository` (继承 JpaRepository) | `UserRepository` |
| **Entity** | 数据库表映射 | `@Entity` | `User` |

---

## 二、完整请求链路（登录示例）

```
1. 前端发送请求
   POST http://localhost:8081/api/v1/users/login
   Body: {"username": "admin", "password": "admin123"}

       ↓

2. Controller 层接收请求
   UserController.login(UserRequest request)
   - 参数校验
   - 调用 Service

       ↓

3. Service 层处理业务
   UserService.authenticateUser("admin", "admin123")
   - 调用 Repository 查询用户
   - 验证密码

       ↓

4. Repository 层查询数据库
   UserRepository.findByUsername("admin")
   → SQL: SELECT * FROM users WHERE username = 'admin'

       ↓

5. Service 层验证密码
   PasswordUtil.matches("admin123", "$2a$10$...加密密码...")

       ↓

6. Controller 层生成 Token
   String token = jwtUtils.generateToken("admin")

       ↓

7. Controller 层返回 HTTP 响应
   {
     "code": 200,
     "message": "Login successful",
     "data": {
       "token": "eyJhbGciOiJIUzI1NiJ9...",
       "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
     }
   }
```

---

## 三、代码结构

### 3.1 Controller 层示例

**文件**：`controller/UserController.java`

```java
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserRequest request) {
        // 1. 参数校验
        if (request.username() == null || request.username().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("code", 400, "message", "Username cannot be empty"));
        }

        // 2. 调用 Service 层
        String username = userService.authenticateUser(
            request.username(),
            request.password()
        );

        // 3. 生成 Token
        String token = jwtUtils.generateToken(username);

        // 4. 返回响应
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "message", "Login successful",
            "data", Map.of("token", token)
        ));
    }
}
```

---

### 3.2 Service 层示例

**文件**：`service/UserService.java`

```java
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public String authenticateUser(String username, String password) {
        // 1. 从数据库查询用户
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException(
                    "Invalid username or password",
                    HttpStatus.UNAUTHORIZED
                ));

        // 2. 验证密码
        if (!PasswordUtil.matches(password, user.getPassword())) {
            throw new CustomException(
                "Invalid username or password",
                HttpStatus.UNAUTHORIZED
            );
        }

        // 3. 返回用户名
        return user.getUsername();
    }
}
```

---

### 3.3 Repository 层示例

**文件**：`repository/UserRepository.java`

```java
public interface UserRepository extends JpaRepository<User, Long> {
    // Spring Data JPA 自动实现方法
    // 根据方法名自动生成 SQL：SELECT * FROM users WHERE username = ?
    Optional<User> findByUsername(String username);
}
```

**自动继承的方法**：
```java
userRepository.findById(1L);        // 根据 ID 查询
userRepository.findAll();           // 查询所有
userRepository.save(user);          // 保存
userRepository.delete(user);        // 删除
userRepository.count();             // 计数
```

---

### 3.4 Entity 层示例

**文件**：`model/User.java`

```java
@Data           // Lombok: 自动生成 getter/setter
@Entity         // JPA: 标记为实体类
@Table(name = "users")
public class User {

    @Id         // 主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    private Role role;  // "USER" 或 "ADMIN"

    private String orgTags;       // 组织标签
    private String primaryOrg;    // 主组织标签

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

---

## 四、其他分层

### 4.1 Client 层（外部 API 客户端）

```
client/
├── DeepSeekClient.java      // DeepSeek LLM API
└── EmbeddingClient.java     // Embedding API
```

### 4.2 Consumer 层（消息消费者）

```
consumer/
└── FileProcessingConsumer.java  // Kafka 文件处理
```

### 4.3 Handler 层（WebSocket 处理器）

```
handler/
└── ChatWebSocketHandler.java  // 聊天 WebSocket
```

### 4.4 Config 层（配置类）

```
config/
├── SecurityConfig.java
├── WebSocketConfig.java
├── KafkaConfig.java
├── RedisConfig.java
├── MinioConfig.java
└── EsConfig.java
```

---

## 五、依赖注入与控制反转

### 5.1 @Autowired 自动装配

```java
@Service
public class UserService {

    // 方式 1：字段注入
    @Autowired
    private UserRepository userRepository;

    // 方式 2：构造器注入（推荐）
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
}
```

### 5.2 常用注解

| 注解 | 作用 | 示例 |
|------|------|------|
| `@SpringBootApplication` | 启动类主注解 | `SmartPaiApplication` |
| `@RestController` | REST 控制器 | `UserController` |
| `@Service` | 服务层 | `UserService` |
| `@Repository` | 数据访问层 | `UserRepository` |
| `@Entity` | JPA 实体 | `User` |
| `@Autowired` | 自动装配 | 构造器或字段注入 |
| `@Transactional` | 事务管理 | Service 方法 |

---

## 六、实践练习

### 练习 1：绘制分层架构图

画出登录请求的完整链路图：
- 前端 → Controller → Service → Repository → 数据库
- 标注每个文件的路径

### 练习 2：追踪 API 请求

使用浏览器开发者工具或 Postman：
1. 发起一个登录请求
2. 查看请求 URL 和响应数据
3. 对应到代码中的 Controller 方法

### 练习 3：查看数据库表结构

```bash
docker exec mysql mysql -u root -pPaiSmart2025 \
  -e "USE PaiSmart; DESCRIBE users;"
```

### 练习 4：添加日志

在 `UserController.login()` 方法中添加：
```java
System.out.println("用户尝试登录：" + request.username());
```

---

## 七、核心文件路径

```
启动类：SmartPaiApplication.java

Controller: controller/UserController.java
Service:    service/UserService.java
Repository: repository/UserRepository.java
Entity:     model/User.java

其他分层:
Client:     client/DeepSeekClient.java
Consumer:   consumer/FileProcessingConsumer.java
Handler:    handler/ChatWebSocketHandler.java
Config:     config/SecurityConfig.java
```

---

## 八、课后总结

### 本课要点

| 知识点 | 关键内容 |
|--------|----------|
| 三层架构 | Controller → Service → Repository |
| Controller 层 | 接收请求、参数校验、返回响应 |
| Service 层 | 业务逻辑、事务控制 |
| Repository 层 | 数据访问、JPA 自动实现 |
| Entity 层 | 数据库表映射 |
| 依赖注入 | @Autowired 自动装配 |

### 下节课预告

**第 3 课：数据模型与数据库设计**
- JPA 实体关系
- 一对一、一对多映射
- 多租户数据隔离

---

*课程版本：2.0*
*更新日期：2026-03-17*
