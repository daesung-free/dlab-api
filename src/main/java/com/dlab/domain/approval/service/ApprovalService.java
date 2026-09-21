package com.dlab.domain.approval.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.*;
import com.dlab.domain.approval.repository.ApprovalItemRepository;
import com.dlab.domain.approval.repository.ApproverPreferenceRepository;
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
    private final ApproverPreferenceRepository preferenceRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final com.dlab.domain.user.service.HomeroomResolver homeroomResolver;
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
                .findByAcademyIdAndYearAndRequestTypeAndDeletedFalse(academy.getId(), year, requestType)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_ITEM_NOT_FOUND,
                        "승인 정책이 없습니다: " + requestType));

        approvalRequestRepository
                .findByEnrollmentIdAndApprovalItemIdAndStatus(enrollment.getId(), item.getId(), ApprovalStatus.PENDING)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 처리 대기중인 신청이 있습니다.");
                });

        Teacher escalationTarget = resolveEscalationTarget(enrollment);
        if (item.hasEscalation() && escalationTarget == null) {
            log.warn("담당선생님이 없는 학생의 승인 신청: enrollmentId={}. 1차 승인자만 처리 가능하다.",
                    enrollment.getId());
        }

        ApproverType primary = resolvePrimaryApprover(enrollment, item);

        ApprovalRequest request = approvalRequestRepository.save(new ApprovalRequest(
                academy, item, enrollment, escalationTarget, primary, Instant.now(clock)));

        notifyRequestCreated(request, escalationTarget);
        return request;
    }

    /**
     * 우선 승인자 결정 — <b>학생 선택 &gt; 지점 정책</b> 순 (0803 답변서).
     *
     * <p>학생이 등록 시 고른 값이 있으면 그것이 우선이고, 없으면 지점 정책값을 쓴다.
     * <b>선택을 강제하지 않는 이유</b>는 이미 등록된 학생들이 있기 때문이다 —
     * 선택이 없다고 신청을 막으면 그 학생들은 아무것도 신청할 수 없게 된다.
     *
     * <p>{@link ApproverType#AUTO}는 학생이 고를 수 없으므로 정책값이 AUTO면 그대로 둔다.
     */
    private ApproverType resolvePrimaryApprover(StudentEnrollment enrollment, ApprovalItem item) {
        if (item.getApproverType() == ApproverType.AUTO) {
            return ApproverType.AUTO;
        }
        return preferenceRepository.findCurrent(enrollment.getId())
                .map(ApproverPreference::getPreferred)
                .orElse(item.getApproverType());
    }

    /**
     * 우선 승인자 선택 + 동의 기록.
     *
     * <p><b>덮어쓰지 않고 행을 쌓는다</b> — 설정이 아니라 동의라서, 바꿨다고 이전 기록을
     * 지우면 그 기간에 무엇에 동의했는지 답할 수 없다.
     *
     * @param terms 동의한 안내 문구. 문구가 미확정이라 지금은 {@code null}로 들어온다
     */
    @Transactional
    public ApproverPreference choosePrimaryApprover(StudentEnrollment enrollment,
                                                    ApproverType preferred,
                                                    com.dlab.domain.appconfig.entity.Terms terms) {
        if (preferred != ApproverType.PARENT && preferred != ApproverType.TEACHER) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "우선 승인자는 학부모 또는 직원만 선택할 수 있습니다.");
        }
        ApproverPreference saved = preferenceRepository.save(
                new ApproverPreference(enrollment, preferred, Instant.now(clock), terms));

        log.info("우선 승인자 선택: enrollmentId={}, 선택={}", enrollment.getId(), preferred);
        return saved;
    }

    /** 현재 우선 승인자 선택값. 없으면 아직 안 골랐다는 뜻이다. */
    @Transactional(readOnly = true)
    public java.util.Optional<ApproverPreference> findPrimaryApprover(Long enrollmentId) {
        return preferenceRepository.findCurrent(enrollmentId);
    }

    /**
     * 자동 재승인 요청 — <b>1회만</b>.
     *
     * <p>학부모가 타임아웃까지 응답하지 않은 건에 한 번 더 알린다.
     * {@code reminderSentAt}이 곧 "보냈다"는 표시라 두 번 돌아도 두 번 나가지 않는다.
     *
     * @return 실제로 보낸 건수
     */
    @Transactional
    public int sendReminders() {
        Instant now = Instant.now(clock);
        List<ApprovalRequest> targets = approvalRequestRepository.findReminderTargets(now);

        for (ApprovalRequest request : targets) {
            request.markReminderSent(now);
            notifyAll(NotificationEvent.APPROVAL_REMINDER, request, reminderVariables(request));
        }
        if (!targets.isEmpty()) {
            log.info("승인 자동 재요청 발송: {}건", targets.size());
        }
        return targets.size();
    }

    /**
     * 직원 이양 — 재요청 후에도 무응답인 건.
     *
     * <p><b>담당선생님이 원래 타임아웃 후에도 승인할 수 있었다.</b> 여기서 바뀌는 건
     * 권한이 아니라 <b>"이제 당신 차례"라고 알리는 것</b>이다 — 알림이 없으면 담당선생님은
     * 대기 목록을 직접 열어보기 전까지 모른다.
     *
     * @return 이양 처리된 건수
     */
    @Transactional
    public int handOverToStaff() {
        Instant now = Instant.now(clock);
        List<ApprovalRequest> handed = approvalRequestRepository.findHandoverCandidates(now).stream()
                .filter(r -> r.needsHandover(now))
                .toList();

        for (ApprovalRequest request : handed) {
            request.markHandedOver(now);

            Teacher target = request.getEscalationTeacher();
            if (target == null) {
                // 반 미배정이거나 담임 미지정. 넘길 사람이 없으면 요청은 계속 대기한다
                log.warn("이양할 담당선생님이 없다: requestId={}", request.getId());
                continue;
            }
            accountRepository.findByTeacherId(target.getId()).ifPresent(account ->
                    notify(NotificationEvent.APPROVAL_HANDED_OVER, account, request,
                            reminderVariables(request)));
            notifyAll(NotificationEvent.APPROVAL_HANDED_OVER, request, reminderVariables(request));
        }
        if (!handed.isEmpty()) {
            log.info("승인 직원 이양: {}건", handed.size());
        }
        return handed.size();
    }

    private Map<String, String> reminderVariables(ApprovalRequest request) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("requestedAt", TIME_FORMAT.format(request.getRequestedAt()));
        variables.put("timeoutMinutes", String.valueOf(request.getTimeoutMinutes()));
        return variables;
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

    /**
     * 신청자 취소.
     *
     * <p>승인자가 처리하는 것과 <b>같은 조건부 UPDATE</b>를 탄다 — 학생이 취소하는 순간
     * 학부모가 승인 버튼을 누를 수 있는데, 두 경로가 다른 방식으로 상태를 바꾸면
     * <b>취소된 신청이 승인된 것으로 남는다.</b> 여기서는 먼저 도착한 쪽만 성공한다.
     *
     * <p>{@code resolverType}·{@code resolver}를 비워 둔다 — 취소는 승인 행위가 아니라
     * 신청 철회다. 신청자를 승인자 자리에 적으면 통계에서 "학생이 승인했다"로 집계된다.
     *
     * <p>알림은 보내지 않는다. 낸 사람이 스스로 거둔 것이라 알릴 상대가 없다.
     */
    @Transactional
    public void cancelByRequester(Long requestId) {
        int updated = approvalRequestRepository.resolveIfPending(
                requestId, ApprovalStatus.CANCELED, Instant.now(clock),
                null, null, null, null);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }
        log.info("승인 요청 신청자 취소: requestId={}", requestId);
    }

    /**
     * 승인 철회 — <b>직원이 승인된 건을 되돌린다</b>.
     *
     * <p><b>학생에게 이 경로를 주지 않는 이유.</b> 사유 신청은 승인되면 그 시간 결석·조퇴가
     * 무단이 아니게 되어 벌점을 면한다. 학생이 직접 되돌릴 수 있으면 <b>승인만 받고
     * 취소해서 벌점을 피하는 길</b>이 생긴다 — 결석은 그대로인데 벌점만 사라진다.
     *
     * <p>그리고 <b>취소 사유에 따라 옳은 결과가 반대</b>다. "병원에 안 가게 됐다"면 정상
     * 등원이니 벌점이 없는 게 맞고, "잘못 신청했다"면 원래 무단이라 붙는 게 맞다.
     * 사람이 판단해야 하는 자리라 직원 경로로 둔다.
     *
     * <p><b>사유를 필수로 받는다.</b> 승인을 되돌린 기록에 이유가 없으면 나중에
     * "왜 무른 거냐"에 답할 수 없다 — 학생·학부모와 다툼이 생기는 지점이다.
     *
     * <p>{@code CANCELED}로 남기되 <b>{@code resolverAccount}를 채운다</b> —
     * 신청자 취소({@link #cancelByRequester})는 그 자리가 비어 있어, 같은 상태값이라도
     * "누가 거뒀는지"로 갈린다.
     */
    @Transactional
    public void revoke(Long requestId, AuthPrincipal me, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "철회 사유는 필수입니다.");
        }
        ApprovalRequest request = approvalRequestRepository.findById(requestId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "승인 요청을 찾을 수 없습니다."));
        if (!me.canAccessAcademy(request.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        Account actor = accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        int updated = approvalRequestRepository.revokeIfApproved(
                requestId, Instant.now(clock), ApproverType.TEACHER, actor, reason);
        if (updated == 0) {
            // 승인된 건이 아니다 — 대기중이면 반려를, 이미 철회됐으면 아무것도 하지 않는다
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED,
                    "승인된 요청만 철회할 수 있습니다.");
        }

        log.info("승인 철회: requestId={}, 처리자={}, 사유={}", requestId, me.accountId(), reason);
    }

    /** 담당선생님은 반 배정 → 반 담임으로 자동 결정된다. 미배정이거나 담임 미지정이면 null. */
    /**
     * 이양 대상 = <b>그 학생의 담임</b>(예외 지정 ?? 반 담임).
     *
     * <p>예외 지정된 학생의 승인은 반 담임이 아니라 지정된 선생님에게 가야 한다 — 그 학생을
     * 맡은 사람이다.
     */
    private Teacher resolveEscalationTarget(StudentEnrollment enrollment) {
        return homeroomResolver.of(enrollment);
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
    /**
     * 대리 처리자의 지점 범위.
     *
     * <p>role 은 계정에 붙어 있고 여기서는 계정만 안다. <b>전 지점 권한 여부를 모르므로
     * 소속 지점이 같은지만 본다</b> — 본사 계정을 막지 않기 위해 소속이 비어 있으면
     * 통과시킨다. 정밀한 판정은 permission 매트릭스(I-12)가 오면 컨트롤러 쪽에서 건다.
     */
    private void verifyAdminScope(ApprovalRequest request, Account approver) {
        if (approver.getEmployee() == null) {
            throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
        }
        Long approverAcademyId = approver.getEmployee().getAcademy() == null
                ? null : approver.getEmployee().getAcademy().getId();
        if (approverAcademyId != null
                && !approverAcademyId.equals(request.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

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

        // 선생님 승인 — 이 요청의 에스컬레이션 대상 본인만 가능하다.
        if (approver.getAccountType() == AccountType.TEACHER) {
            Teacher teacher = approver.getTeacher();
            Teacher target = request.getEscalationTeacher();
            if (target == null || !target.getId().equals(teacher.getId())) {
                throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
            }
            return ApproverType.TEACHER;
        }

        // ★ 관리자 대리 처리 (F-4.1-6).
        //   요구사항이 "실시간 확인/수정·승인"이고, DSA는 애초에 관리자 직접 처리
        //   구조만 있었다 — 승인 라우팅은 그것을 확장한 것이지 관리자를 뺀 것이 아니다.
        //   이걸 막으면 관리자 웹의 승인 대기 화면이 조회 전용이 된다.
        //
        //   ⚠️ 어느 role 까지 열지는 I-12(승인 주체 매트릭스) 대기다.
        //   지금은 지점 범위만 확인한다 — 남의 지점 학생 건을 처리하면 안 된다.
        if (approver.getAccountType() == AccountType.EMPLOYEE) {
            verifyAdminScope(request, approver);
            return ApproverType.ADMIN;
        }

        throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
    }

    private void notifyRequestCreated(ApprovalRequest request, Teacher escalationTarget) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("requestedAt", TIME_FORMAT.format(request.getRequestedAt()));
        variables.put("timeoutMinutes", String.valueOf(request.getTimeoutMinutes()));

        notifyAll(NotificationEvent.APPROVAL_REQUEST_CREATED, request, variables);

        if (escalationTarget != null) {
            accountRepository.findByTeacherId(escalationTarget.getId()).ifPresent(account ->
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
            // 늦은 승인도 같은 문구다 — 학부모가 승인했다는 사실은 같다
            case PARENT_IN_TIME, PARENT_AFTER_TIMEOUT ->
                    NotificationEvent.APPROVAL_APPROVED_BY_PARENT;
            case STAFF_AFTER_TIMEOUT -> NotificationEvent.APPROVAL_APPROVED_AFTER_TIMEOUT;
            case STAFF_BEFORE_TIMEOUT -> NotificationEvent.APPROVAL_APPROVED_BEFORE_TIMEOUT;
            case STAFF_PRIMARY -> NotificationEvent.APPROVAL_APPROVED_BY_STAFF_PRIMARY;
            case ADMIN_PROXY -> NotificationEvent.APPROVAL_APPROVED_BY_ADMIN;
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
                request.getEnrollment().getYear(),
                variables,
                null));
    }
}
