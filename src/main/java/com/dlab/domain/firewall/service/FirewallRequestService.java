package com.dlab.domain.firewall.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.firewall.entity.*;
import com.dlab.domain.firewall.repository.FirewallRequestRepository;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.ParentStudentRepository;
import com.dlab.domain.user.repository.StudentRepository;
import com.dlab.domain.user.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 방화벽 해제 신청과 승인.
 *
 * <p>승인은 타임아웃 기반 에스컬레이션 모델이다(CLAUDE.md §3). 신청 시 학부모와 담당선생님(사감)
 * 양쪽에 알림이 나가지만, 학부모가 기본 승인자이고 타임아웃(10분)이 지나면 담당선생님이 승인한다.
 * 실제 승인이 언제·누구에 의해 이뤄졌는지에 따라 결과가 3케이스로 갈리며 안내 문구가 서로 달라야 한다.
 *
 * <p>학부모 승인과 담당선생님 승인이 같은 순간에 들어올 수 있으므로 상태 전이는
 * {@link FirewallRequestRepository#resolveIfPending} 조건부 UPDATE로 원자적으로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirewallRequestService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(TimeConfig.KST);

    private final FirewallRequestRepository firewallRequestRepository;
    private final StudentRepository studentRepository;
    private final UserAccountRepository userAccountRepository;
    private final ParentStudentRepository parentStudentRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /** 학생이 해제를 신청한다. 학부모와 담당선생님에게 동시에 알림이 나간다. */
    @Transactional
    public FirewallRequest create(Long studentId, String reason) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        firewallRequestRepository.findByStudentIdAndStatus(studentId, FirewallStatus.PENDING)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 처리 대기중인 신청이 있습니다.");
                });

        // 에스컬레이션 승인자는 반 배정에서 자동으로 도출된다. 학생별 사전지정 UI는 없다(CLAUDE.md §3).
        Staff escalationStaff = student.getHomeroomStaff();
        if (escalationStaff == null) {
            log.warn("담당선생님이 없는 학생의 방화벽 신청: studentId={}. 학부모 승인만 가능하다.", studentId);
        }

        FirewallRequest request = firewallRequestRepository.save(new FirewallRequest(
                student.getBranch(), student, reason,
                FirewallRequest.DEFAULT_TIMEOUT_MINUTES, escalationStaff, Instant.now(clock)));

        notifyRequestCreated(request, student, escalationStaff);
        return request;
    }

    /**
     * 승인 처리.
     *
     * @throws BusinessException 다른 승인자가 이미 처리했으면 {@code APPROVAL_ALREADY_PROCESSED}
     */
    @Transactional
    public FirewallRequest approve(Long requestId, Long approverAccountId) {
        FirewallRequest request = loadPending(requestId);
        UserAccount approver = loadAccount(approverAccountId);
        ApproverType approverType = resolveApproverType(request, approver);

        Instant now = Instant.now(clock);
        ResolutionCase resolutionCase = request.decideResolutionCase(approverType, now);

        int updated = firewallRequestRepository.resolveIfPending(
                requestId, FirewallStatus.APPROVED, now, approver, approverType, resolutionCase, null);
        if (updated == 0) {
            // 조회와 갱신 사이에 반대편 승인자가 먼저 처리한 경우
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }

        FirewallRequest resolved = firewallRequestRepository.findById(requestId).orElseThrow();
        notifyApproved(resolved, resolutionCase);
        return resolved;
    }

    @Transactional
    public FirewallRequest reject(Long requestId, Long approverAccountId, String rejectReason) {
        FirewallRequest request = loadPending(requestId);
        UserAccount approver = loadAccount(approverAccountId);
        ApproverType approverType = resolveApproverType(request, approver);

        Instant now = Instant.now(clock);
        int updated = firewallRequestRepository.resolveIfPending(
                requestId, FirewallStatus.REJECTED, now, approver, approverType, null, rejectReason);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }

        FirewallRequest resolved = firewallRequestRepository.findById(requestId).orElseThrow();
        Student student = resolved.getStudent();
        notify(NotificationEvent.FIREWALL_REJECTED, student.getUserAccount(), student,
                resolvedVariables(resolved), null);
        return resolved;
    }

    private FirewallRequest loadPending(Long requestId) {
        FirewallRequest request = firewallRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIREWALL_REQUEST_NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }
        return request;
    }

    private UserAccount loadAccount(Long accountId) {
        return userAccountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 이 계정이 이 신청을 처리할 자격이 있는지 확인하고 승인 주체 유형을 돌려준다.
     * 학부모는 해당 학생에 연결된 경우만, 선생님은 이 신청의 에스컬레이션 승인자(또는 상위 관리자)만 가능하다.
     */
    private ApproverType resolveApproverType(FirewallRequest request, UserAccount approver) {
        if (approver.getRole() == UserRole.PARENT) {
            boolean linked = parentStudentRepository
                    .findByStudentIdWithAccount(request.getStudent().getId()).stream()
                    .anyMatch(ps -> ps.getParent().getUserAccount().getId().equals(approver.getId()));
            if (!linked) {
                throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
            }
            return ApproverType.PARENT;
        }

        if (approver.getRole() == UserRole.SUPER_ADMIN) {
            return ApproverType.STAFF;
        }

        if (approver.getRole() == UserRole.STAFF_HOMEROOM) {
            Staff escalationStaff = request.getEscalationStaff();
            boolean isAssignedStaff = escalationStaff != null
                    && escalationStaff.getUserAccount().getId().equals(approver.getId());
            if (!isAssignedStaff) {
                throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
            }
            return ApproverType.STAFF;
        }

        throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
    }

    private void notifyRequestCreated(FirewallRequest request, Student student, Staff escalationStaff) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("requestedAt", TIME_FORMAT.format(request.getRequestedAt()));
        variables.put("timeoutMinutes", String.valueOf(request.getTimeoutMinutes()));

        for (UserAccount parentAccount : parentAccountsOf(student)) {
            notify(NotificationEvent.FIREWALL_REQUEST_CREATED, parentAccount, student, variables, null);
        }
        if (escalationStaff != null) {
            notify(NotificationEvent.FIREWALL_REQUEST_CREATED,
                    escalationStaff.getUserAccount(), student, variables, null);
        }
    }

    /**
     * 승인 결과 알림. 케이스별로 이벤트가 달라지고, 그래서 문구도 달라진다.
     * 특히 STAFF_AFTER_TIMEOUT("시간이 지나 담임이 승인")과 STAFF_BEFORE_TIMEOUT("시간이 남았지만
     * 담임이 먼저 승인")은 학부모 입장에서 전혀 다른 상황이므로 절대 같은 문구로 합치지 말 것(CLAUDE.md §3).
     */
    private void notifyApproved(FirewallRequest request, ResolutionCase resolutionCase) {
        Student student = request.getStudent();
        Map<String, String> variables = resolvedVariables(request);

        NotificationEvent event = switch (resolutionCase) {
            case PARENT_IN_TIME -> NotificationEvent.FIREWALL_APPROVED_BY_PARENT;
            case STAFF_AFTER_TIMEOUT -> NotificationEvent.FIREWALL_APPROVED_AFTER_TIMEOUT;
            case STAFF_BEFORE_TIMEOUT -> NotificationEvent.FIREWALL_APPROVED_BEFORE_TIMEOUT;
        };

        notify(event, student.getUserAccount(), student, variables, null);
        for (UserAccount parentAccount : parentAccountsOf(student)) {
            notify(event, parentAccount, student, variables, null);
        }
    }

    private Map<String, String> resolvedVariables(FirewallRequest request) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("resolvedAt", TIME_FORMAT.format(request.getResolvedAt()));
        variables.put("timeoutMinutes", String.valueOf(request.getTimeoutMinutes()));
        return variables;
    }

    private List<UserAccount> parentAccountsOf(Student student) {
        return parentStudentRepository.findByStudentIdWithAccount(student.getId()).stream()
                .map(ps -> ps.getParent().getUserAccount())
                .toList();
    }

    private void notify(NotificationEvent event, UserAccount recipient, Student student,
                        Map<String, String> variables, String dedupKey) {
        notificationService.send(
                NotificationCommand.forStudent(event, recipient, student, variables, dedupKey));
    }
}
