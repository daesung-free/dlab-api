package com.dlab.domain.approval.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.approval.entity.*;
import com.dlab.domain.approval.repository.ApprovalItemRepository;
import com.dlab.domain.approval.repository.ApprovalRequestRepository;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
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
 * 공통 승인 라우팅.
 *
 * <p>사유신청·정기일정·방화벽이 전부 이 서비스를 탄다. 각 도메인이 자기 승인 로직을 갖게 하면
 * 같은 것을 세 번 짜게 되고, 특히 타임아웃·에스컬레이션·3케이스 판별처럼 틀리기 쉬운 부분이
 * 조금씩 다르게 구현된다.
 *
 * <p>승인 모델은 단순 레이스가 아니라 타임아웃 기반 에스컬레이션이다. 신청 시 학부모와
 * 담당선생님에게 동시에 알림이 가지만, 승인 시점에 따라 결과가 3케이스로 갈리고
 * <b>학부모에게 나가는 문구가 케이스마다 달라야 한다</b>.
 *
 * <p>학부모 승인과 담당선생님 승인이 정확히 같은 순간 들어올 수 있으므로, 상태 전이는
 * {@link ApprovalRequestRepository#resolveIfPending} 조건부 UPDATE로 원자적으로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(TimeConfig.KST);

    private final ApprovalItemRepository approvalItemRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final AccountRepository accountRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /**
     * 승인 요청 생성. 담당선생님은 반 배정에서 자동으로 도출된다
     * — 그래서 학생별 승인자 사전지정 화면이 필요 없다.
     */
    @Transactional
    public ApprovalRequest create(StudentEnrollment enrollment, RequestType requestType) {
        Academy academy = enrollment.getAcademy();
        short year = enrollment.getYear();

        ApprovalItem item = approvalItemRepository
                .findByAcademyIdAndYearAndRequestType(academy.getId(), year, requestType)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_ITEM_NOT_FOUND,
                        "승인 정책이 없습니다: " + requestType));

        approvalRequestRepository
                .findByEnrollmentIdAndApprovalItemIdAndStatus(enrollment.getId(), item.getId(), ApprovalStatus.PENDING)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 처리 대기중인 신청이 있습니다.");
                });

        Employee escalationTarget = resolveEscalationTarget(enrollment);
        if (item.hasEscalation() && escalationTarget == null) {
            log.warn("담당선생님이 없는 학생의 승인 신청: enrollmentId={}. 1차 승인자만 처리 가능하다.",
                    enrollment.getId());
        }

        ApprovalRequest request = approvalRequestRepository.save(
                new ApprovalRequest(academy, year, item, enrollment, escalationTarget, Instant.now(clock)));

        notifyRequestCreated(request, escalationTarget);
        return request;
    }

    /**
     * 승인 처리.
     *
     * @throws BusinessException 반대편이 이미 처리했으면 {@code APPROVAL_ALREADY_PROCESSED}
     */
    @Transactional
    public ApprovalRequest approve(Long requestId, Long approverAccountId) {
        ApprovalRequest request = loadPending(requestId);
        Account approver = loadAccount(approverAccountId);
        ApproverType approverType = resolveApproverType(request, approver);

        Instant now = Instant.now(clock);
        ResolutionCase resolutionCase = request.decideResolutionCase(approverType, now);

        int updated = approvalRequestRepository.resolveIfPending(
                requestId, ApprovalStatus.APPROVED, now, approverType, approver, resolutionCase, null);
        if (updated == 0) {
            // 조회와 갱신 사이에 반대편 승인자가 먼저 처리한 경우
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }

        ApprovalRequest resolved = approvalRequestRepository.findById(requestId).orElseThrow();
        notifyApproved(resolved, resolutionCase);
        return resolved;
    }

    @Transactional
    public ApprovalRequest reject(Long requestId, Long approverAccountId, String rejectReason) {
        ApprovalRequest request = loadPending(requestId);
        Account approver = loadAccount(approverAccountId);
        ApproverType approverType = resolveApproverType(request, approver);

        Instant now = Instant.now(clock);
        int updated = approvalRequestRepository.resolveIfPending(
                requestId, ApprovalStatus.REJECTED, now, approverType, approver, null, rejectReason);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }

        ApprovalRequest resolved = approvalRequestRepository.findById(requestId).orElseThrow();
        notifyAll(NotificationEvent.APPROVAL_REJECTED, resolved, resolvedVariables(resolved));
        return resolved;
    }

    /** 담당선생님은 반 배정 → 반 담임으로 자동 결정된다. 미배정이거나 담임 미지정이면 null. */
    private Employee resolveEscalationTarget(StudentEnrollment enrollment) {
        return classAssignmentRepository.findActiveFixedByEnrollmentId(enrollment.getId())
                .map(ClassAssignment::getHomeroomEmployee)
                .orElse(null);
    }

    private ApprovalRequest loadPending(Long requestId) {
        ApprovalRequest request = approvalRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_REQUEST_NOT_FOUND));
        if (!request.isPending()) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }
        return request;
    }

    private Account loadAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 이 계정이 이 요청을 처리할 자격이 있는지 확인하고 승인 주체 유형을 돌려준다.
     * 학부모는 해당 학생에 연결된 경우만, 선생님은 이 요청의 에스컬레이션 대상(또는 상위 관리자)만 가능하다.
     */
    private ApproverType resolveApproverType(ApprovalRequest request, Account approver) {
        Long studentId = request.getEnrollment().getStudent().getId();

        if (approver.getAccountType() == AccountType.PARENT) {
            boolean linked = accountRepository.findGuardianAccountsOfStudent(studentId).stream()
                    .anyMatch(a -> a.getId().equals(approver.getId()));
            if (!linked) {
                throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
            }
            return ApproverType.PARENT;
        }

        if (approver.getAccountType() == AccountType.EMPLOYEE) {
            Employee employee = approver.getEmployee();
            if (employee.getEmployeeType() == EmployeeType.SUPER) {
                return ApproverType.TEACHER;
            }
            Employee target = request.getEscalationEmployee();
            if (target == null || !target.getId().equals(employee.getId())) {
                throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
            }
            return ApproverType.TEACHER;
        }

        throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
    }

    private void notifyRequestCreated(ApprovalRequest request, Employee escalationTarget) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("requestedAt", TIME_FORMAT.format(request.getRequestedAt()));
        variables.put("timeoutMinutes", String.valueOf(request.getTimeoutMinutes()));

        notifyAll(NotificationEvent.APPROVAL_REQUEST_CREATED, request, variables);

        if (escalationTarget != null) {
            accountRepository.findByEmployeeId(escalationTarget.getId()).ifPresent(account ->
                    notify(NotificationEvent.APPROVAL_REQUEST_CREATED, account, request, variables));
        }
    }

    /**
     * 승인 결과 알림. 케이스별로 이벤트가 달라지고, 그래서 문구도 달라진다.
     *
     * <p>특히 {@code STAFF_AFTER_TIMEOUT}("시간이 지나 담임이 승인")과
     * {@code STAFF_BEFORE_TIMEOUT}("시간이 남았지만 담임이 먼저 승인")은 학부모 입장에서
     * 전혀 다른 상황이므로 절대 같은 문구로 합치지 말 것.
     */
    private void notifyApproved(ApprovalRequest request, ResolutionCase resolutionCase) {
        NotificationEvent event = switch (resolutionCase) {
            case PARENT_IN_TIME -> NotificationEvent.APPROVAL_APPROVED_BY_PARENT;
            case STAFF_AFTER_TIMEOUT -> NotificationEvent.APPROVAL_APPROVED_AFTER_TIMEOUT;
            case STAFF_BEFORE_TIMEOUT -> NotificationEvent.APPROVAL_APPROVED_BEFORE_TIMEOUT;
        };
        notifyAll(event, request, resolvedVariables(request));
    }

    private Map<String, String> resolvedVariables(ApprovalRequest request) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("resolvedAt", TIME_FORMAT.format(request.getResolvedAt()));
        variables.put("timeoutMinutes", String.valueOf(request.getTimeoutMinutes()));
        return variables;
    }

    /** 학생 본인 + 연결된 학부모 전원. */
    private void notifyAll(NotificationEvent event, ApprovalRequest request, Map<String, String> variables) {
        Student student = request.getEnrollment().getStudent();

        accountRepository.findByStudentId(student.getId())
                .ifPresent(account -> notify(event, account, request, variables));

        List<Account> guardians = accountRepository.findGuardianAccountsOfStudent(student.getId());
        for (Account guardian : guardians) {
            notify(event, guardian, request, variables);
        }
    }

    private void notify(NotificationEvent event, Account recipient,
                        ApprovalRequest request, Map<String, String> variables) {
        notificationService.send(NotificationCommand.forStudent(
                event,
                recipient,
                request.getEnrollment().getStudent(),
                request.getAcademy(),
                request.getYear(),
                variables,
                null));
    }
}
