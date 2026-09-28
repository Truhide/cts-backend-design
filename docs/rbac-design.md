# 团队三层权限模型设计

## 1. 角色定义

| 角色 | 含义 | 关键权限 |
|---|---|---|
| `OWNER` | 团队创建者，唯一 | 解散团队、添加成员、移除成员、调整成员角色、创建/删除/分配任务 |
| `ADMIN` | 团队管理员 | 创建任务、删除任务、分配任务 |
| `MEMBER` | 普通成员 | 浏览团队任务；仅可修改**分配给自己的**任务状态 |

角色持久化在 `team_members` 表上，与团队成员关系一一对应：

```java
@Entity
@Table(name = "team_members",
       uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "user_id"}))
public class TeamMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false) private Long teamId;
    @Column(name = "user_id", nullable = false) private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamRole role;

    @Column(name = "joined_at") private LocalDateTime joinedAt;
}
```

用 `(team_id, user_id)` 联合唯一约束在**数据库层**兜住"同一用户重复加入同一团队"，
避免只依赖业务代码判断。

---

## 2. 三条设计取舍

### 2.1 权限授予收敛至唯一所有者

**ADMIN 不能授予或撤销他人角色，只有 OWNER 可以。**

理由：如果 ADMIN 之间可以互相授权，就能通过"你把我设成 ADMIN、我把你设成 ADMIN"
形成多数派，绕过唯一所有者。**管理权的授予必须只由 OWNER 操作**，这是权限系统的
收敛点，不能下放。

```java
if (!team.getOwnerId().equals(operatorId)) {
    return Result.error("仅团队拥有者可调整成员权限");
}
if (targetRole == null || targetRole == TeamRole.OWNER) {
    return Result.error("仅可设置ADMIN/MEMBER角色");
}
if (targetUserId.equals(operatorId)) {
    return Result.error("不能修改自己的角色");
}
```

### 2.2 OWNER 不能直接退出团队

**原因**：如果允许 OWNER 直接退队，团队会进入「没有所有者、无人能解散或转让」的
孤儿状态，这个团队及其任务将永远无法被管理。

```java
if (member.getRole() == TeamRole.OWNER) {
    return Result.error("团队拥有者不能直接离开团队，请先解散团队或转让所有权");
}
```

### 2.3 团队采用软删除

**`status` 字段：`1` = 正常，`0` = 已解散。**

**原因**：团队解散后，历史任务仍需保留其所属团队关系。如果物理删除团队记录，
这些任务会失去归属，既无法追溯，也说不清"这些任务原本属于哪个团队"。

因此：

- 解散团队只是把 `status` 置为 `0`
- **所有查询都带上 `status = 1` 条件**过滤已解散团队

```java
Optional<Team> findByIdAndStatus(Long id, Integer status);
boolean existsByIdAndStatus(Long id, Integer status);
```

解散时同时给团队下的每个任务写入一条"团队解散，任务仍保留为团队任务"的日志，
保证变更可追溯。

---

## 3. 成员变动时的任务归属一致性

成员被移出团队或主动退队时，其名下已分配的团队任务需要处理，否则会出现
**"任务挂在一个已不属于该团队的成员名下"** 的脏数据。

处理方式：

1. 查询该成员在团队中被分配的所有任务
2. 逐条将 `assigneeId` 置空（解除分配）
3. 为每条任务写入一条 `MEMBER_LEAVE_TASK_CHANGE` 日志
4. 最后删除成员关系

```java
List<Task> assignedTasks = taskRepository.findByTeamIdAndAssigneeId(teamId, targetUserId);
assignedTasks.forEach(task -> {
    task.setAssigneeId(null);
    taskRepository.save(task);
    try {
        activityLogService.createLog(task.getId(), removedUsername,
                ActionType.MEMBER_LEAVE_TASK_CHANGE,
                TargetType.TASK_ASSIGNEE,
                "成员被移除团队，任务取消分配",
                ScopeType.TEAM, teamId);
    } catch (Exception e) {
        log.warn("成员移除的日志记录失败，任务ID: {}", task.getId(), e);
    }
});
teamMemberRepository.deleteByTeamIdAndUserId(teamId, targetUserId);
```

**注意这里日志写入的异常是被吞掉的**——审计日志是附属能力，不应因为写日志失败
就导致「成员退队」这个业务动作整体失败。这个取舍在
[`audit-log-design.md`](audit-log-design.md) 中有详细说明。

---

## 4. 权限校验的调用方式

所有权限判断统一走 `AccessControlService`，并且**用户身份只能来自认证上下文**：

```java
// 从 JWT 拦截器写入的 request attribute 取当前用户，不接受前端传参
Long userId = (Long) httpRequest.getAttribute("userId");

// 权限判断
accessControlService.hasTeamManagePermission(teamId, userId)
accessControlService.isTeamOwner(teamId, userId)
accessControlService.isTeamMember(teamId, userId)
accessControlService.getTeamRole(teamId, userId)
```

**为什么强调"不接受前端传参"**：如果接口允许前端传 `userId`，那么任何登录用户
只要伪造请求体就能冒充他人执行操作。身份必须由服务端从令牌中解析。
