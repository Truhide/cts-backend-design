package com.example.common.security;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.taskpanel.model.Task;
import com.example.taskpanel.repository.TaskRepository;
import com.example.team.model.Team;
import com.example.team.model.TeamMember;
import com.example.team.model.enums.TeamRole;
import com.example.team.repository.TeamMemberRepository;
import com.example.team.repository.TeamRepository;

/**
 * 集中式鉴权组件。
 *
 * <p>设计说明：本类只依赖 Repository，不依赖任何业务 Service，因此不会引入循环依赖。
 * 之前的实现中，日志模块为了做存在性校验，直接调用 {@code TeamService} 和 {@code TaskService}，
 * 导致 {@code TeamService} ↔ {@code ActivityLogService} 互相注入，只能靠 {@code @Lazy} 掩盖，
 * 并且用了一个硬编码的“系统管理员 ID”去过权限校验，等于在自己的权限模型上开了后门。
 * 现在把「资源是否存在」与「当前用户是否有权」两类判断独立成组件，
 * 调用方必须显式传入真实的 userId，不再存在绕过路径。
 *
 * <p>统一约定：团队有效状态为 {@link #TEAM_STATUS_ACTIVE}（1 = 正常，0 = 已解散软删除）。
 */
@Service
public class AccessControlService {

    /** 团队正常状态（未解散） */
    public static final int TEAM_STATUS_ACTIVE = 1;

    private final TaskRepository taskRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    public AccessControlService(TaskRepository taskRepository,
                                TeamRepository teamRepository,
                                TeamMemberRepository teamMemberRepository) {
        this.taskRepository = taskRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    // ==================== 存在性校验（与权限无关） ====================

    /**
     * 任务是否存在。注意：这是纯存在性判断，不代表当前用户有权访问。
     */
    public boolean taskExists(Long taskId) {
        return taskId != null && taskRepository.existsById(taskId);
    }

    /**
     * 团队是否存在且未解散。
     */
    public boolean teamExists(Long teamId) {
        return teamId != null && teamRepository.existsByIdAndStatus(teamId, TEAM_STATUS_ACTIVE);
    }

    /**
     * 个人任务（不属于任何团队的任务）是否存在。
     */
    public boolean personalTaskExists(Long taskId) {
        return findPersonalTask(taskId).isPresent();
    }

    // ==================== 权限校验 ====================

    /**
     * 指定用户是否为个人任务（teamId 为空的任务）的创建者。
     */
    public boolean isPersonalTaskOwner(Long taskId, Long userId) {
        if (taskId == null || userId == null) {
            return false;
        }
        return taskRepository.findById(taskId)
                .map(task -> task.getTeamId() == null && userId.equals(task.getCreatorId()))
                .orElse(false);
    }

    /**
     * 指定用户是否为团队成员（隐含要求团队未解散）。
     */
    public boolean isTeamMember(Long teamId, Long userId) {
        if (teamId == null || userId == null || !teamExists(teamId)) {
            return false;
        }
        return teamMemberRepository.existsByTeamIdAndUserId(teamId, userId);
    }

    /**
     * 获取用户在团队中的角色；不是有效团队的成员时返回 null。
     */
    public TeamRole getTeamRole(Long teamId, Long userId) {
        if (teamId == null || userId == null || !teamExists(teamId)) {
            return null;
        }
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .map(TeamMember::getRole)
                .orElse(null);
    }

    /**
     * 指定用户是否拥有团队的管理权限（OWNER 或 ADMIN）。
     */
    public boolean hasTeamManagePermission(Long teamId, Long userId) {
        TeamRole role = getTeamRole(teamId, userId);
        return role == TeamRole.OWNER || role == TeamRole.ADMIN;
    }

    /**
     * 指定用户是否为团队拥有者。
     */
    public boolean isTeamOwner(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return false;
        }
        return teamRepository.findByIdAndStatus(teamId, TEAM_STATUS_ACTIVE)
                .map(team -> userId.equals(team.getOwnerId()))
                .orElse(false);
    }

    /**
     * 指定用户是否有权查看某个团队任务。
     *
     * <p>规则：用户必须是该团队成员，且该任务确实属于这个团队。
     * 这里显式校验 task.teamId 与 teamId 一致，避免通过伪造 teamId 越权读取其他团队的任务。
     */
    public boolean canViewTeamTask(Long teamId, Long taskId, Long userId) {
        if (!isTeamMember(teamId, userId)) {
            return false;
        }
        return taskRepository.findById(taskId)
                .map(task -> teamId.equals(task.getTeamId()))
                .orElse(false);
    }

    // ==================== 查询辅助 ====================

    /**
     * 按 ID 查询个人任务（不属于任何团队），不存在时返回空。
     */
    public Optional<Task> findPersonalTask(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return taskRepository.findById(taskId)
                .filter(task -> task.getTeamId() == null);
    }

    /**
     * 按 ID 查询团队任务，并校验该任务确实属于指定团队。
     */
    public Optional<Task> findTeamTask(Long teamId, Long taskId) {
        if (teamId == null || taskId == null) {
            return Optional.empty();
        }
        return taskRepository.findById(taskId)
                .filter(task -> teamId.equals(task.getTeamId()));
    }

    /**
     * 查询团队实体（未解散）。
     */
    public Optional<Team> findActiveTeam(Long teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return teamRepository.findByIdAndStatus(teamId, TEAM_STATUS_ACTIVE);
    }
}
