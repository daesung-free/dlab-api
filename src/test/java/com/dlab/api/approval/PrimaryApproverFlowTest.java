package com.dlab.api.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.entity.ResolutionCase;
import com.dlab.domain.approval.repository.ApprovalRequestRepository;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 우선 승인자 · 자동 재승인 · 직원 이양 (I-20, 0803 답변서).
 *
 * <p>지키려는 것 — <b>학생 선택이 지점 정책을 이긴다</b>, <b>동의는 덮어쓰지 않고 쌓인다</b>,
 * <b>재요청은 한 번만</b>, <b>재요청 뒤 한 번 더 기다렸다가 이양한다</b>,
 * <b>직원 우선이면 타임아웃 판정을 하지 않는다</b>.
 */
@SpringBootTest
@Transactional
class PrimaryApproverFlowTest {

    private static final short TIMEOUT = 10;

    @Autowired ApprovalService approvalService;
    @Autowired com.dlab.domain.notification.repository.NotificationLogRepository
            notificationLogRepository;
    @Autowired ApprovalRequestRepository requestRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;
    @MockitoBean com.dlab.domain.approval.service.ApprovalReminderScheduler reminderScheduler;

    Academy academy;
    StudentEnrollment enrollment;
    Account guardianAccount;
    Account teacherAccount;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) java.time.LocalDate.now(clock).getYear();

        academy = new Academy("PA01", "우선승인자지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher teacher = new Teacher(academy, "담임쌤", "010-1000-0001");
        em.persist(teacher);
        teacherAccount = Account.forTeacher(teacher, "PATCH01", "x", false);
        em.persist(teacherAccount);

        ClassMaster classMaster = new ClassMaster(academy, year, "1반", ClassType.FIXED, teacher);
        em.persist(classMaster);

        Student student = new Student("PASTU001", "선택학생", "010-2000-0001");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, year, "0001", "RF-PA01",
                GradeType.N_SU);
        em.persist(enrollment);
        em.persist(new ClassAssignment(academy, enrollment, classMaster, ClassType.FIXED));

        ParentGuardian guardian = new ParentGuardian("학부모", "010-3000-0001", "F");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(student, guardian, (short) 1, true));
        guardianAccount = Account.forGuardian(guardian, "PAPAR01", "x");
        em.persist(guardianAccount);

        // 지점 정책: 학부모 1차 → 10분 → 담당선생님
        em.persist(new ApprovalItem(academy, year, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, TIMEOUT, ApproverType.TEACHER));
        em.flush();
    }

    @Test
    @DisplayName("★★ 승인 요청 알림에 「무엇을 승인하는지」가 실린다 — 없으면 알림이 통째로 보류된다")
    void notificationCarriesRequestType() {
        request();
        em.flush();
        em.clear();

        var logs = em.createQuery("""
                SELECT l FROM NotificationLog l
                WHERE l.eventCode = 'APPROVAL_REQUEST_CREATED'
                ORDER BY l.id DESC
                """, com.dlab.domain.notification.entity.NotificationLog.class)
                .setMaxResults(1).getResultList();

        assertThat(logs).isNotEmpty();
        var sent = logs.get(0);

        // ★ 학부모는 「승인 요청이 왔습니다」만 보면 **와이파이인지 외출인지 모른 채** 승인한다
        assertThat(sent.getVariables()).containsEntry("requestType", "와이파이 해제");

        // ★★ 변수가 빠지면 예외가 아니라 **조용히 보류**된다 — 그래서 테스트로 묶는다.
        //    `required_variables` 에 넣고 서비스가 안 채우면 그 알림이 영영 안 나간다.
        //
        //    ⚠️ 「보류가 아님」으로 보면 안 된다 — **발송 구현체가 아직 0개**라
        //    지금은 모든 알림이 「발송 구현체 없음」으로 보류된다(그건 정상이다).
        //    가려야 할 것은 **보류 사유**다
        assertThat(sent.getFailReason()).doesNotContain("변수 누락");
    }

    @Test
    @DisplayName("★★ 승인 결과 알림에도 승인항목이 실린다 — 접수톡에만 있으면 「무엇이」 승인됐는지 모른다")
    void approvedNotificationCarriesRequestType() {
        ApprovalRequest r = request();
        approvalService.approve(r.getId(), teacherAccount.getId());
        em.flush();
        em.clear();

        var sent = lastLog("APPROVAL_APPROVED_BEFORE_TIMEOUT");

        // ★ 신청이 여러 건 걸려 있으면 「승인되었습니다」만으로는 어느 것인지 알 수 없다
        assertThat(sent.getVariables()).containsEntry("requestType", "와이파이 해제");

        // ★★ 이 변수는 required_variables 에도 들어가 있다 — 서비스가 안 채우면
        //    예외가 아니라 **조용히 보류**된다. 그래서 사유를 본다
        assertThat(sent.getFailReason()).doesNotContain("변수 누락");
    }

    @Test
    @DisplayName("★★ 재요청톡은 비활성이라 나가지 않는다 — 그래도 재요청·이양 흐름은 그대로 돈다")
    void reminderTemplateIsInactive() {
        ApprovalRequest r = request();
        rewind(r, TIMEOUT + 1);

        assertThat(approvalService.sendReminders()).isEqualTo(1);
        em.flush();
        em.clear();

        // ★ 발송은 막혔지만 **흐름은 살아 있다** — 이벤트·스케줄러를 지우면
        //   「재요청 후 같은 대기시간을 한 번 더 준 뒤 이양」이 깨져서,
        //   학부모가 알림을 보고 들어올 틈 없이 바로 이양된다
        assertThat(em.find(ApprovalRequest.class, r.getId()).getReminderSentAt()).isNotNull();

        var sent = lastLog("APPROVAL_REMINDER");
        assertThat(sent.getStatus())
                .isEqualTo(com.dlab.domain.notification.entity.NotificationStatus.SKIPPED);
        assertThat(sent.getFailReason()).contains("비활성 템플릿");
    }

    private com.dlab.domain.notification.entity.NotificationLog lastLog(String eventCode) {
        var logs = em.createQuery("""
                        SELECT l FROM NotificationLog l
                        WHERE l.eventCode = :event
                        ORDER BY l.id DESC
                        """, com.dlab.domain.notification.entity.NotificationLog.class)
                .setParameter("event", com.dlab.domain.notification.entity.NotificationEvent
                        .valueOf(eventCode))
                .setMaxResults(1).getResultList();
        assertThat(logs).as("%s 로그가 없다", eventCode).isNotEmpty();
        return logs.get(0);
    }

    private ApprovalRequest request() {
        ApprovalRequest r = approvalService.create(enrollment, RequestType.FIREWALL_UNLOCK);
        em.flush();
        return r;
    }

    /** 시각을 앞당길 수 없으므로 요청의 기준선을 과거로 밀어 "시간이 지난" 상황을 만든다. */
    private void rewind(ApprovalRequest request, int minutes) {
        em.createNativeQuery("""
                        UPDATE approval_request
                           SET requested_at = requested_at - make_interval(mins => :m),
                               escalation_at = escalation_at - make_interval(mins => :m)
                         WHERE id = :id
                        """)
                .setParameter("m", minutes)
                .setParameter("id", request.getId())
                .executeUpdate();
        em.clear();
    }

    // ── 선택 · 동의 ──────────────────────────────────────

    @Test
    @DisplayName("★ 안 골라도 적용값이 나온다 — 「미설정」만 내리면 화면이 매번 고르게 만든다")
    void effectiveApproverFallsBackToBranchPolicy() {
        var items = approvalService.effectiveApprovers(enrollment);

        var firewall = items.stream()
                .filter(i -> i.requestType() == RequestType.FIREWALL_UNLOCK)
                .findFirst().orElseThrow();

        assertThat(firewall.effective()).isEqualTo(ApproverType.PARENT);
        assertThat(firewall.source()).isEqualTo(ApprovalService.ApproverSource.BRANCH_POLICY);
        assertThat(firewall.branchDefault()).isEqualTo(ApproverType.PARENT);
        assertThat(firewall.timeoutMinutes()).isEqualTo(TIMEOUT);
        assertThat(firewall.escalationApproverType()).isEqualTo(ApproverType.TEACHER);
    }

    @Test
    @DisplayName("★ 학생이 고르면 출처가 STUDENT_CHOICE다 — 관리자 설정이 안 먹히는 이유가 여기다")
    void effectiveApproverShowsStudentChoice() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                guardianAccount);
        em.flush();

        var firewall = approvalService.effectiveApprovers(enrollment).stream()
                .filter(i -> i.requestType() == RequestType.FIREWALL_UNLOCK)
                .findFirst().orElseThrow();

        assertThat(firewall.effective()).isEqualTo(ApproverType.TEACHER);
        assertThat(firewall.source()).isEqualTo(ApprovalService.ApproverSource.STUDENT_CHOICE);
        // ★ 지점 정책값도 함께 내린다 — 둘이 다르면 선택이 덮고 있다는 뜻이다
        assertThat(firewall.branchDefault()).isEqualTo(ApproverType.PARENT);
    }

    @Test
    @DisplayName("정책이 없는 유형은 목록에서 빠진다 — 빈 값을 끼우면 「승인자 없음」으로 그려진다")
    void typesWithoutPolicyAreOmitted() {
        // 설정한 것은 방화벽 하나뿐이다
        assertThat(approvalService.effectiveApprovers(enrollment))
                .extracting(ApprovalService.EffectiveApprover::requestType)
                .containsExactly(RequestType.FIREWALL_UNLOCK);
    }

    @Test
    @DisplayName("★ 학생이 고른 우선 승인자가 지점 정책을 이긴다")
    void studentChoiceBeatsBranchPolicy() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                guardianAccount);
        em.flush();

        // 지점 정책은 PARENT인데 학생이 TEACHER를 골랐다
        assertThat(request().getPrimaryApprover()).isEqualTo(ApproverType.TEACHER);
    }

    @Test
    @DisplayName("선택이 없으면 지점 정책을 따른다 — 기존 학생들이 신청을 못 하면 안 된다")
    void fallsBackToBranchPolicy() {
        assertThat(request().getPrimaryApprover()).isEqualTo(ApproverType.PARENT);
    }

    @Test
    @DisplayName("★ 선택을 바꾸면 덮어쓰지 않고 이력이 쌓인다 — 동의 기록이라 지우면 안 된다")
    void choiceIsAppendOnly() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.PARENT, null, guardianAccount);
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                guardianAccount);
        em.flush();
        em.clear();

        Long count = em.createQuery("""
                SELECT COUNT(p) FROM ApproverPreference p WHERE p.enrollment.id = :id
                """, Long.class).setParameter("id", enrollment.getId()).getSingleResult();

        assertThat(count).isEqualTo(2);
        // 현재값은 마지막 것
        assertThat(approvalService.findPrimaryApprover(enrollment.getId()))
                .get().extracting(p -> p.getPreferred()).isEqualTo(ApproverType.TEACHER);
    }

    @Test
    @DisplayName("★ AUTO는 고를 수 없다 — 학생이 자동 승인을 고르면 승인 절차가 무의미해진다")
    void autoCannotBeChosen() {
        assertThatThrownBy(() ->
                approvalService.choosePrimaryApprover(enrollment, ApproverType.AUTO, null, guardianAccount))
                .isInstanceOf(BusinessException.class);
    }

    // ── 자동 재요청 ──────────────────────────────────────

    @Test
    @DisplayName("★ 타임아웃이 지나면 자동 재요청이 나가고, 두 번은 안 나간다")
    void reminderIsSentOnlyOnce() {
        ApprovalRequest r = request();
        rewind(r, TIMEOUT + 1);

        assertThat(approvalService.sendReminders()).isEqualTo(1);
        em.flush();
        em.clear();

        // 두 번째 실행에는 안 걸린다
        assertThat(approvalService.sendReminders()).isZero();
        assertThat(em.find(ApprovalRequest.class, r.getId()).getReminderSentAt()).isNotNull();
    }

    @Test
    @DisplayName("타임아웃 전에는 재요청이 안 나간다")
    void noReminderBeforeTimeout() {
        request();
        assertThat(approvalService.sendReminders()).isZero();
    }

    @Test
    @DisplayName("★ 직원 우선인 건에는 재요청이 안 나간다 — 기다리지도 않는 학부모를 독촉하게 된다")
    void noReminderWhenStaffIsPrimary() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                guardianAccount);
        ApprovalRequest r = request();
        rewind(r, TIMEOUT + 1);

        assertThat(approvalService.sendReminders()).isZero();
    }

    // ── 직원 이양 ────────────────────────────────────────

    @Test
    @DisplayName("★ 재요청 직후에는 이양하지 않는다 — 학부모가 알림 보고 들어올 틈을 준다")
    void handoverWaitsAfterReminder() {
        ApprovalRequest r = request();
        rewind(r, TIMEOUT + 1);
        approvalService.sendReminders();
        em.flush();

        assertThat(approvalService.handOverToStaff()).isZero();
    }

    @Test
    @DisplayName("★ 재요청 후 대기시간이 또 지나면 직원에게 이양된다")
    void handoverAfterSecondWait() {
        ApprovalRequest r = request();
        rewind(r, TIMEOUT + 1);
        approvalService.sendReminders();
        em.flush();

        // 재요청 시각도 과거로 민다
        em.createNativeQuery("""
                        UPDATE approval_request
                           SET reminder_sent_at = reminder_sent_at - make_interval(mins => :m)
                         WHERE id = :id
                        """)
                .setParameter("m", TIMEOUT + 1)
                .setParameter("id", r.getId())
                .executeUpdate();
        em.clear();

        assertThat(approvalService.handOverToStaff()).isEqualTo(1);
        em.flush();
        em.clear();

        assertThat(em.find(ApprovalRequest.class, r.getId()).getHandedOverAt()).isNotNull();
        // 이양은 한 번만
        assertThat(approvalService.handOverToStaff()).isZero();
    }

    @Test
    @DisplayName("이미 처리된 건은 재요청·이양 대상이 아니다")
    void resolvedIsNotTargeted() {
        ApprovalRequest r = request();
        approvalService.approve(r.getId(), guardianAccount.getId());
        em.flush();
        rewind(r, TIMEOUT + 1);

        assertThat(approvalService.sendReminders()).isZero();
        assertThat(approvalService.handOverToStaff()).isZero();
    }

    // ── 결과 케이스 ──────────────────────────────────────

    @Test
    @DisplayName("★ 직원 우선이면 STAFF_PRIMARY다 — '시간 남았는데 담임이 먼저'와 문구가 달라야 한다")
    void staffPrimaryIsItsOwnCase() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                guardianAccount);
        ApprovalRequest r = request();

        ApprovalRequest resolved = approvalService.approve(r.getId(), teacherAccount.getId());

        assertThat(resolved.getResolutionCase()).isEqualTo(ResolutionCase.STAFF_PRIMARY);
    }

    @Test
    @DisplayName("학부모 우선일 때 담임이 타임아웃 전에 승인하면 STAFF_BEFORE_TIMEOUT 그대로다")
    void parentPrimaryKeepsExistingCases() {
        ApprovalRequest r = request();

        ApprovalRequest resolved = approvalService.approve(r.getId(), teacherAccount.getId());

        assertThat(resolved.getResolutionCase()).isEqualTo(ResolutionCase.STAFF_BEFORE_TIMEOUT);
    }

    @Test
    @DisplayName("학부모가 승인하면 PARENT_IN_TIME이다")
    void parentApprovalKeepsCase() {
        ApprovalRequest r = request();

        ApprovalRequest resolved = approvalService.approve(r.getId(), guardianAccount.getId());

        assertThat(resolved.getResolutionCase()).isEqualTo(ResolutionCase.PARENT_IN_TIME);
        assertThat(resolved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("우선 승인자는 신청 시점 스냅샷이다 — 나중에 바꿔도 처리된 건이 흔들리면 안 된다")
    void primaryApproverIsSnapshot() {
        ApprovalRequest r = request();
        assertThat(r.getPrimaryApprover()).isEqualTo(ApproverType.PARENT);

        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                guardianAccount);
        em.flush();
        em.clear();

        assertThat(em.find(ApprovalRequest.class, r.getId()).getPrimaryApprover())
                .isEqualTo(ApproverType.PARENT);
    }

    @Test
    @DisplayName("재요청 대상 조회는 대기 중인 것만 본다")
    void reminderTargetsOnlyPending() {
        ApprovalRequest r = request();
        rewind(r, TIMEOUT + 1);

        assertThat(requestRepository.findReminderTargets(Instant.now(clock)))
                .extracting(ApprovalRequest::getId)
                .containsExactly(r.getId());

        approvalService.approve(r.getId(), guardianAccount.getId());
        em.flush();
        em.clear();

        assertThat(requestRepository.findReminderTargets(
                Instant.now(clock).plus(1, ChronoUnit.HOURS))).isEmpty();
    }

    // ── 승인 철회 (앱 시안 p4 · 3) ────────────────────────────

    @Test
    @DisplayName("★★ 승인된 건을 직원이 되돌린다 — 학생이 못 하는 이유는 벌점 회피 때문이다")
    void staffRevokesApproved() {
        ApprovalRequest r = request();
        approvalService.approve(r.getId(), guardianAccount.getId());
        em.flush();
        em.clear();

        approvalService.revoke(r.getId(), staffPrincipal(), "학생이 정상 등원함");
        em.flush();
        em.clear();

        ApprovalRequest after = requestRepository.findById(r.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ApprovalStatus.CANCELED);
        assertThat(after.getRejectReason()).isEqualTo("학생이 정상 등원함");
        // 신청자 취소와 갈리는 지점 — 그쪽은 이 자리가 비어 있다
        assertThat(after.getResolverAccount()).isNotNull();
    }

    @Test
    @DisplayName("★ 대기중인 건은 철회가 아니라 반려다 — 승인된 것만 되돌린다")
    void pendingCannotBeRevoked() {
        ApprovalRequest r = request();
        em.flush();

        assertThatThrownBy(() -> approvalService.revoke(r.getId(), staffPrincipal(), "사유"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 사유 없이는 철회할 수 없다 — 나중에 \"왜 무른 거냐\"에 답해야 한다")
    void revokeRequiresReason() {
        ApprovalRequest r = request();
        approvalService.approve(r.getId(), guardianAccount.getId());
        em.flush();

        assertThatThrownBy(() -> approvalService.revoke(r.getId(), staffPrincipal(), "  "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다른 지점 건은 철회하지 못한다")
    void cannotRevokeOtherAcademy() {
        ApprovalRequest r = request();
        approvalService.approve(r.getId(), guardianAccount.getId());
        em.flush();

        var other = com.dlab.common.security.AuthPrincipal.of(
                teacherAccount.getId(), "TEACHER", academy.getId() + 999,
                java.util.List.of(com.dlab.common.security.Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> approvalService.revoke(r.getId(), other, "사유"))
                .isInstanceOf(BusinessException.class);
    }

    private com.dlab.common.security.AuthPrincipal staffPrincipal() {
        return com.dlab.common.security.AuthPrincipal.of(
                teacherAccount.getId(), "TEACHER", academy.getId(),
                java.util.List.of(com.dlab.common.security.Role.TEACHER), false);
    }

    // ── 지정 주체가 학부모로 옮겨졌다 (디랩 1029 · 1031) ──────────

    /**
     * ★★ <b>누가 골랐는지</b>가 남는다.
     *
     * <h2>★ 왜 필요한가 — 부·모 둘 다 고를 수 있다</h2>
     * 현재값은 <b>가장 최근 행</b>이라, 어머니가 고른 뒤 아버지가 바꾸면 값이 바뀐다.
     * ⚠️ 이 값이 없으면 <b>다른 보호자에게 알릴 수도, 「내가 고른 값이 왜 바뀌었나」에
     * 답할 수도 없다.</b>
     */
    @Test
    @DisplayName("★★ 누가 골랐는지 남는다 — 없으면 「내 설정이 왜 바뀌었나」에 답할 수 없다")
    void recordsWhoChose() {
        var saved = approvalService.choosePrimaryApprover(
                enrollment, ApproverType.TEACHER, null, guardianAccount);
        em.flush();

        assertThat(saved.getChosenByAccount()).isNotNull();
        assertThat(saved.getChosenByAccount().getId()).isEqualTo(guardianAccount.getId());
    }

    /**
     * ★ <b>처음 고르는 것은 알리지 않는다.</b>
     *
     * <p>「바뀜」이 아니라 「정해짐」이고 <b>아직 아무도 기대하던 값이 없다.</b>
     * ⚠️ 여기서 보내면 <b>가입 직후 모든 학부모가 「승인자가 변경되었습니다」를 받는다.</b>
     */
    @Test
    @DisplayName("★ 처음 고를 때는 변경 알림이 없다 — 「바뀜」이 아니라 「정해짐」이다")
    void firstChoiceSendsNoNotification() {
        approvalService.choosePrimaryApprover(
                enrollment, ApproverType.TEACHER, null, guardianAccount);
        em.flush();

        assertThat(notificationLogRepository.findAll())
                .extracting(log -> log.getEventCode().name())
                .doesNotContain("APPROVER_PREFERENCE_CHANGED");
    }

    /**
     * ★ <b>같은 값을 다시 눌러도 알리지 않는다.</b>
     *
     * <p>설정 화면에서 같은 값을 다시 누르는 일이 실제로 있다. ⚠️ <b>바뀌지 않았는데
     * 「바뀌었습니다」가 가면 그 알림을 믿지 않게 된다.</b>
     */
    @Test
    @DisplayName("★ 같은 값을 다시 골라도 알림이 없다 — 안 바뀐 알림이 가면 알림을 안 믿는다")
    void sameValueSendsNoNotification() {
        approvalService.choosePrimaryApprover(
                enrollment, ApproverType.TEACHER, null, guardianAccount);
        em.flush();
        approvalService.choosePrimaryApprover(
                enrollment, ApproverType.TEACHER, null, guardianAccount);
        em.flush();

        assertThat(notificationLogRepository.findAll())
                .extracting(log -> log.getEventCode().name())
                .doesNotContain("APPROVER_PREFERENCE_CHANGED");
    }

    /**
     * ★★ <b>다른 보호자가 바꾸면 알린다</b> (디랩 1029:
     * <b>「모르는 사이에 승인자가 바뀌면 안 됩니다」</b>).
     *
     * <p>★ <b>고른 사람에게는 보내지 않는다</b> — 자기가 한 일이다.
     */
    @Test
    @DisplayName("★★ 다른 보호자가 바꾸면 알린다 — 고친 사람에게는 보내지 않는다")
    void notifiesOtherGuardianOnChange() {
        ParentGuardian father = new ParentGuardian("아버지", "010-3000-0002", "M");
        em.persist(father);
        em.persist(new StudentGuardianLink(enrollment.getStudent(), father, (short) 2, false));
        Account fatherAccount = Account.forGuardian(father, "PAPAR02", "x");
        em.persist(fatherAccount);
        em.flush();

        // 어머니가 「부모」로 정한 뒤 아버지가 「담임」으로 바꾼다
        approvalService.choosePrimaryApprover(
                enrollment, ApproverType.PARENT, null, guardianAccount);
        em.flush();
        approvalService.choosePrimaryApprover(
                enrollment, ApproverType.TEACHER, null, fatherAccount);
        em.flush();

        var changed = notificationLogRepository.findAll().stream()
                .filter(log -> "APPROVER_PREFERENCE_CHANGED".equals(log.getEventCode().name()))
                .toList();

        assertThat(changed)
                .as("★ 어머니에게만 간다 — 바꾼 아버지에게는 보내지 않는다")
                .hasSize(1);
        assertThat(changed.get(0).getRecipient().getId())
                .isEqualTo(guardianAccount.getId());
    }
}
