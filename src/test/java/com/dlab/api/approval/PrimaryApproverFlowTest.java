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
    @DisplayName("★ 학생이 고른 우선 승인자가 지점 정책을 이긴다")
    void studentChoiceBeatsBranchPolicy() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null);
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
        approvalService.choosePrimaryApprover(enrollment, ApproverType.PARENT, null);
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null);
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
                approvalService.choosePrimaryApprover(enrollment, ApproverType.AUTO, null))
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
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null);
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
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null);
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

        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null);
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
}
