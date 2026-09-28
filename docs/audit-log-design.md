# 任务操作审计日志设计

## 1. 目标

记录"**谁、在什么时候、对哪个任务、做了什么**"，并满足三个要求：

1. **无歧义**：一条日志能唯一确定一次操作，不依赖上下文推测
2. **可回溯**：任务被删除后，其历史操作仍可查
3. **不拖累主业务**：审计是附属能力，不应因为它失败而让业务操作失败

---

## 2. 三维枚举建模

单靠一个"操作类型"字段无法无歧义地描述操作，因此用三个维度组合：

| 枚举 | 回答的问题 | 取值 |
|---|---|---|
| `ActionType` | **做了什么** | `TASK_CREATED` 创建任务<br>`TASK_UPDATED` 修改基本信息<br>`STATUS_CHANGED` 修改状态<br>`ASSIGNEE_CHANGED` 修改负责人<br>`DEPENDENCY_CHANGED` 修改依赖<br>`TASK_DELETED` 删除任务<br>`MEMBER_LEAVE_TASK_CHANGE` 成员离开导致任务归属变化<br>`TEAM_DISSOLVE_TASK_CHANGE` 团队解散导致任务状态变化 |
| `TargetType` | **作用于什么** | `PERSONAL_TASK` / `TEAM_TASK` / `TASK_ASSIGNEE` / `TASK_DEPENDENCY` |
| `ScopeType` | **属于哪个范围** | `PERSONAL`（个人任务）/ `TEAM`（团队任务） |

举例说明为什么需要三维：同样是 `STATUS_CHANGED`，作用在
`PERSONAL_TASK`（个人任务）和 `TEAM_TASK`（团队任务）上，其查询权限、
检索范围都不同。只用 `ActionType` 一个维度无法区分。

**扩展性**：这个建模方式为后续「按任务 / 按人 / 按团队」的多维检索留出空间，
新增操作类型只需扩展枚举，不需要改表结构。

---

## 3. 范围隔离

日志表用 `scopeType` + `scopeId` 两个字段表达所属范围：

| 范围 | `scopeType` | `scopeId` |
|---|---|---|
| 个人任务日志 | `PERSONAL` | **固定为 `null`** |
| 团队任务日志 | `TEAM` | 该团队的 `teamId` |

写入时对范围做**防御性校验**：

```java
if (ScopeType.PERSONAL.equals(scopeType)) {
    if (scopeId != null) {
        log.warn("个人任务日志传入非空 scopeId: {}, 强制置为 null", scopeId);
        scopeId = null;
    }
} else if (ScopeType.TEAM.equals(scopeType)) {
    if (scopeId == null) {
        throw new IllegalArgumentException("团队任务日志scopeId（团队ID）不能为空");
    }
    if (!accessControlService.teamExists(scopeId)) {
        throw new IllegalArgumentException("团队不存在或已解散，团队ID: " + scopeId);
    }
}
```

个人任务传入 `scopeId` 时**纠正 + 告警**而不是抛异常——因为它不影响数据正确性，
只是调用方疏忽；团队任务缺失 `scopeId` 则**直接拒绝**，因为那会导致日志无法归属。

---

## 4. 事务一致性的取舍

### 4.1 常规场景：日志与业务同事务

任务的创建、更新、删除等**单条**日志，与业务操作在同一个事务内：
保证不会出现"业务成功但日志丢失"。

```java
@Override
@Transactional
public void createLog(Long taskId, String operatorUsername, ActionType actionType,
                      TargetType targetType, String description,
                      ScopeType scopeType, Long scopeId) { ... }
```

### 4.2 批量留痕：尽力而为

成员退出、团队解散等场景需要对其名下**多条任务**逐个写日志。
这里采用**尽力而为**策略：单条日志失败只记告警，**不回滚主操作**。

```java
try {
    activityLogService.createLog(task.getId(), removedUsername, ...);
} catch (Exception e) {
    log.warn("成员移除的日志记录失败，任务ID: {}", task.getId(), e);
}
```

**取舍理由**：审计日志是附属能力。如果因为写日志失败就让
"成员退队"这个业务动作整体失败，用户会陷入"点了没反应、不知道为什么"的状态，
而这个失败对用户毫无意义。**正确的做法是让业务成功、记录告警、由监控发现日志缺口**，
而不是让主业务为附属能力陪葬。

---

## 5. 已删除任务的日志回溯

任务被物理删除后，它的日志仍然存在，但此时已经无法通过任务表判断"这个请求者
是否有权看这些日志"。

**解决方案**：通过日志表中的**创建记录**回溯归属。

```java
if (accessControlService.personalTaskExists(taskId)) {
    // 任务仍在：按所有权正常校验
    if (!accessControlService.isPersonalTaskOwner(taskId, currentUserId)) {
        throw new LogAccessDeniedException("无权限查看该个人任务日志，任务ID: " + taskId);
    }
} else {
    // 任务已删除：通过日志中「创建了任务」的记录回溯原创建者
    boolean wasCreator = activityLogRepository
            .existsByTaskIdAndScopeTypeAndOperatorUsernameAndActionType(
                    taskId, ScopeType.PERSONAL, currentUsername, ActionType.TASK_CREATED);
    if (!wasCreator) {
        throw new TaskNotFoundException("个人任务不存在，任务ID: " + taskId);
    }
}
```

即：**谁创建了这个任务，谁就能看它的历史操作记录**，即使任务本体已经不存在。

**代价**：依赖"创建记录一定存在"这一前提。如果日志本身有缺口（见 4.2 的
尽力而为策略），回溯会失效，此时返回"任务不存在"。这个边界是可以接受的——
查询一个既无任务记录、也无创建日志的 `taskId`，本身就应当返回不存在。

---

## 6. 权限校验的分层

日志查询的权限判断明确分成**两步**，而不是混在一个布尔值里：

| 步骤 | 判断内容 | 失败时抛出 | HTTP |
|---|---|---|---|
| 1 | 资源是否存在 | `TaskNotFoundException` | 404 |
| 2 | 当前用户是否有权 | `LogAccessDeniedException` | 403 |

团队任务日志的校验链：

```java
if (!accessControlService.teamExists(teamId)) {
    throw new TaskNotFoundException("团队不存在或已解散，团队ID: " + teamId);
}
if (accessControlService.findTeamTask(teamId, taskId).isEmpty()) {
    throw new TaskNotFoundException("团队任务不存在，任务ID: " + taskId);
}
if (!accessControlService.isTeamMember(teamId, currentUserId)) {
    throw new LogAccessDeniedException("非团队成员，无权限查看日志，团队ID: " + teamId);
}
if (!accessControlService.canViewTeamTask(teamId, taskId, currentUserId)) {
    throw new LogAccessDeniedException("无权限查看该团队任务日志，任务ID: " + taskId);
}
```

**注意 `findTeamTask(teamId, taskId)`**：它的查询条件**本身就包含团队归属**，
避免通过伪造 `teamId` 越权读取其他团队的任务日志。

---

## 7. 改进方向

- 日志表随业务增长会持续膨胀，需要考虑按时间归档或分区；
- 「创建记录回溯归属」依赖日志完整性，可引入更可靠的归属字段；
- 批量留痕可改为异步队列 + 重试，兼顾主业务吞吐与日志完整性。
