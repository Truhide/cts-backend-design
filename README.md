# 协作式任务管理系统 · 后端模块设计与实现

> **仓库说明**
>
> 这是一个**多人协作的课程项目**。本仓库**不包含完整项目源码**，仅整理**本人负责模块**的设计思路、接口设计与关键实现片段，用于说明该模块的工程取舍。
>
> **本人负责范围：团队模块（团队与成员管理）** 与 **任务日志模块（操作审计日志）**。

---

## 1. 项目背景

一个前后端分离的协同任务管理系统，支持个人任务与团队任务两类场景。系统整体能力包括：

- 用户注册、登录与基于 JWT 的无状态认证
- 个人任务的增删改查、状态与优先级管理、任务间依赖关系
- 团队协作：团队与成员管理、基于角色的权限控制、任务分配
- 任务操作日志：对任务全生命周期的操作留痕，支持个人与团队两种范围

**技术栈**：`Java 21` · `Spring Boot 3.1.5` · `Spring Data JPA` · `MySQL 8` · `JWT (jjwt)` · `Maven`

---

## 2. 本人负责模块

### 2.1 团队模块（`com.example.team`）

负责团队与团队成员的完整生命周期管理，以及贯穿其中的权限控制。

| 能力 | 说明 |
|---|---|
| 创建团队 | 创建者自动成为 OWNER，写入成员关系表 |
| 团队列表 | 仅返回未解散的团队，并附带当前用户在该团队的角色 |
| 团队详情 | 含成员列表、成员角色与加入时间 |
| 成员管理 | 添加成员、移除成员、调整成员角色 |
| 主动退队 | 成员自行退出（OWNER 被禁止直接退出） |
| 解散团队 | 软删除，保留任务与成员记录 |

**权限模型：三层角色**

| 角色 | 权限 |
|---|---|
| OWNER（拥有者） | 解散团队、添加/移除成员、调整成员角色、创建/删除/分配任务 |
| ADMIN（管理员） | 创建/删除/分配任务 |
| MEMBER（普通成员） | 浏览团队任务；仅可修改**分配给自己的**任务状态 |

详细设计见 [`docs/rbac-design.md`](docs/rbac-design.md)。

### 2.2 任务日志模块（`com.example.tasklog`）

一套任务操作审计日志，记录"谁在什么时候对哪个任务做了什么"。

- 用 `ActionType` / `TargetType` / `ScopeType` **三维枚举**建模操作语义
- 通过 `ScopeType` + `scopeId` 区分个人任务与团队任务的日志
- 日志写入与主业务操作**同事务**；批量留痕场景采用尽力而为策略
- 任务被删除后，仍可通过日志中的创建记录**回溯归属**，保证历史可查

详细设计见 [`docs/audit-log-design.md`](docs/audit-log-design.md)。

---

## 3. 鉴权组件的重构

模块初版实现中，日志服务为完成"资源存在性校验"，直接反向调用了团队服务，形成
`TeamService ↔ ActivityLogService` 的**循环依赖**，只能靠 `@Lazy` 让容器启动；
并且为了绕过权限上下文，使用了一个**固定用户 ID** 作为校验身份。

这带来两个问题：

1. **固定用户 ID 等于在权限模型上开了后门** —— 任何真实用户都可以借该身份通过校验
2. 循环依赖是分层不清的症状，`@Lazy` 只是掩盖而非解决

**重构方案**：抽出只依赖 Repository 的 `AccessControlService`，集中提供鉴权判断，
不依赖任何业务 Service，从根本上消除循环依赖。

```
AccessControlService
├── 存在性校验
│     taskExists / teamExists / personalTaskExists
├── 权限校验
│     isPersonalTaskOwner / isTeamMember / getTeamRole
│     hasTeamManagePermission / isTeamOwner / canViewTeamTask
└── 查询辅助
      findPersonalTask / findTeamTask / findActiveTeam
```

重构后的效果：

- 依赖关系变为**单向**（`TeamService → AccessControlService ← ActivityLogService`），`@Lazy` 移除
- **所有用户身份一律来自 JWT 拦截器写入的 request attribute**，不接受前端传参
- 「资源是否存在」（不存在 → `TaskNotFoundException` / 404）与「用户是否有权」
  （无权 → `LogAccessDeniedException` / 403）**严格分开**，不再混在同一个布尔判断里

关键实现见 [`snippets/AccessControlService.java`](snippets/AccessControlService.java)。

---

## 4. 其他优化

- **消除 N+1 查询**：团队列表、团队详情、成员列表原先在循环中逐条 `findById` 查询
  用户与团队信息，成员数为 N 时产生 N 次额外查询。改为
  「收集 ID → `findAllById` 批量查询 → 构建 Map → 内存组装」，查询次数由 O(N) 降为 O(1)。
- **权限校验解耦**：将资源存在性校验与权限校验拆分到独立方法，避免"用权限接口做存在性判断"的混用。

---

## 5. 已知不足与改进方向

- 团队任务列表当前为全量取回内存后排序分页，数据量增大时应下推到数据库层；
- 鉴权组件与任务服务之间存在部分权限判断重复，可进一步收敛；
- 审计日志采用"创建记录回溯归属"的方式支持已删除任务查询，日志量增大后需要考虑归档策略。

---

## 6. 目录结构

```
.
├── README.md                       # 本文件：项目背景与模块总览
├── docs/
│   ├── rbac-design.md              # 三层权限模型设计
│   └── audit-log-design.md         # 审计日志设计
└── snippets/
    ├── AccessControlService.java   # 集中式鉴权组件
    └── ActivityLogServiceImpl.java # 审计日志服务实现
```

---

## 7. 技术栈

| 分类 | 技术 |
|---|---|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.1.5 |
| 持久化 | Spring Data JPA / Hibernate |
| 数据库 | MySQL 8 |
| 认证 | JWT (jjwt 0.11.5) + BCrypt |
| 构建 | Maven |
