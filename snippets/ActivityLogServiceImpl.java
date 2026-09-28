package com.example.tasklog.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.auth.model.User;
import com.example.auth.repository.UserRepository;
import com.example.common.security.AccessControlService;
import com.example.tasklog.entity.ActivityLog;
import com.example.tasklog.enums.ActionType;
import com.example.tasklog.enums.ScopeType;
import com.example.tasklog.enums.TargetType;
import com.example.tasklog.exception.LogAccessDeniedException;
import com.example.tasklog.exception.TaskNotFoundException;
import com.example.tasklog.repository.ActivityLogRepository;
import com.example.tasklog.service.ActivityLogService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 日志业务逻辑实现。
 *
 * <p>重构要点（对照修复前的版本）：
 * <ol>
 *   <li>删除了硬编码的 {@code getSystemAdminId()}。原实现把「1L」当作管理员 ID 传给
 *       任务的权限校验接口来做存在性判断，任何真实用户都能借这个 ID 通过校验，
 *       相当于在权限模型上留了后门。现在权限校验一律使用调用方传入的真实 userId。</li>
 *   <li>不再注入 {@code TeamService} / {@code TaskService}，改用只依赖 Repository 的
 *       {@link AccessControlService}，从而彻底消除 {@code TeamService} ↔
 *       {@code ActivityLogService} 的循环依赖，{@code TeamService} 里的
 *       {@code @Lazy} 也随之移除。</li>
 *   <li>区分「资源是否存在」（不存在 → {@link TaskNotFoundException}）与
 *       「当前用户是否有权」（无权 → {@link LogAccessDeniedException}），
 *       不再把两者混在同一个 boolean 判断里。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityLogServiceImpl implements ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final UserRepository userRepository;
    private final AccessControlService accessControlService;

    /**
     * 记录日志（仅操作成功时调用）
     */
    @Override
    @Transactional
    public void createLog(Long taskId, String operatorUsername, ActionType actionType,
                          TargetType targetType, String description, ScopeType scopeType, Long scopeId) {
        // 1. 参数校验（防御性编程）
        if (taskId == null) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        if (operatorUsername == null || operatorUsername.isBlank()) {
            throw new IllegalArgumentException("操作人用户名不能为空");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("操作类型不能为空");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("目标类型不能为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("操作描述不能为空");
        }
        if (scopeType == null) {
            throw new IllegalArgumentException("所属范围类型不能为空");
        }

        // 2. 范围ID规则校验：个人任务 scopeId 必须为 null，团队任务 scopeId 不能为空
        if (ScopeType.PERSONAL.equals(scopeType)) {
            if (scopeId != null) {
                log.warn("个人任务日志传入非空 scopeId: {}, 强制置为 null", scopeId);
                scopeId = null;
            }
        } else if (ScopeType.TEAM.equals(scopeType)) {
            if (scopeId == null) {
                throw new IllegalArgumentException("团队任务日志 scopeId（团队ID）不能为空");
            }
            if (!accessControlService.teamExists(scopeId)) {
                throw new IllegalArgumentException("团队不存在或已解散，团队ID: " + scopeId);
            }
        }

        // 3. 纯存在性校验（与权限无关，因此不需要任何用户上下文）
        if (!taskExistsForScope(taskId, scopeType, scopeId)) {
            throw new TaskNotFoundException("任务不存在，任务ID: " + taskId);
        }

        // 4. 构建并保存日志
        ActivityLog activityLog = new ActivityLog();
        activityLog.setTaskId(taskId);
        activityLog.setOperatorUsername(operatorUsername);
        activityLog.setActionType(actionType);
        activityLog.setTargetType(targetType);
        activityLog.setDescription(description);
        activityLog.setScopeType(scopeType);
        activityLog.setScopeId(scopeId);

        try {
            activityLogRepository.save(activityLog);
            log.info("任务日志创建成功，任务ID: {}, 操作类型: {}, 操作人: {}, 范围类型: {}",
                    taskId, actionType.getDesc(), operatorUsername, scopeType.getDesc());
        } catch (Exception e) {
            log.error("任务日志保存失败，任务ID: {}, 操作人: {}", taskId, operatorUsername, e);
            throw new IllegalStateException("日志保存失败：" + e.getMessage(), e);
        }
    }

    /**
     * 获取个人任务日志（仅任务所属用户可查看）。
     * 若任务已被删除，通过 activity_log 表中的 TASK_CREATED 记录验证归属。
     */
    @Override
    public List<ActivityLog> getPersonalTaskLogs(Long taskId, String currentUsername) {
        if (taskId == null) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        Long currentUserId = requireUserId(currentUsername);

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

        List<ActivityLog> logs = activityLogRepository
                .findByTaskIdAndScopeTypeOrderByCreatedAtDesc(taskId, ScopeType.PERSONAL);
        log.info("获取个人任务日志成功，任务ID: {}, 用户ID: {}, 日志数量: {}",
                taskId, currentUserId, logs.size());
        return logs;
    }

    /**
     * 获取团队任务日志（需为团队成员且有权查看该任务）。
     */
    @Override
    public List<ActivityLog> getTeamTaskLogs(Long teamId, Long taskId, String currentUsername) {
        if (teamId == null) {
            throw new IllegalArgumentException("团队ID不能为空");
        }
        if (taskId == null) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        Long currentUserId = requireUserId(currentUsername);

        // 1. 资源是否存在（用真实的存在性判断，而不是拿管理员ID去试探权限接口）
        if (!accessControlService.teamExists(teamId)) {
            throw new TaskNotFoundException("团队不存在或已解散，团队ID: " + teamId);
        }
        if (accessControlService.findTeamTask(teamId, taskId).isEmpty()) {
            throw new TaskNotFoundException("团队任务不存在，任务ID: " + taskId);
        }

        // 2. 权限校验：必须是团队成员，且该任务确实属于这个团队
        if (!accessControlService.isTeamMember(teamId, currentUserId)) {
            throw new LogAccessDeniedException("非团队成员，无权限查看日志，团队ID: " + teamId);
        }
        if (!accessControlService.canViewTeamTask(teamId, taskId, currentUserId)) {
            throw new LogAccessDeniedException("无权限查看该团队任务日志，任务ID: " + taskId);
        }

        List<ActivityLog> logs = activityLogRepository
                .findByTaskIdAndScopeTypeAndScopeIdOrderByCreatedAtDesc(taskId, ScopeType.TEAM, teamId);
        log.info("获取团队任务日志成功，团队ID: {}, 任务ID: {}, 用户ID: {}, 日志数量: {}",
                teamId, taskId, currentUserId, logs.size());
        return logs;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 任务存在性校验（纯数据判断，不涉及权限）。
     */
    private boolean taskExistsForScope(Long taskId, ScopeType scopeType, Long scopeId) {
        if (ScopeType.PERSONAL.equals(scopeType)) {
            return accessControlService.taskExists(taskId);
        }
        // 团队任务：任务必须存在，且归属团队与 scopeId 一致
        return accessControlService.findTeamTask(scopeId, taskId).isPresent();
    }

    /**
     * 将当前用户名解析为用户ID；用户不存在时抛鉴权异常。
     */
    private Long requireUserId(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("当前用户名不能为空");
        }
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new LogAccessDeniedException("用户不存在，用户名: " + username));
    }
}
