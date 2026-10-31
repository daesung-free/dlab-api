package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.repository.ApprovalRequestRepository;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.entity.AbsenceSubmitterType;
import com.dlab.domain.attendance.service.AbsenceReasonService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★★ <b>학부모가 앱에서 사유를 낸다</b> (디랩 1031 ③).
 *
 * <h2>★ 왜 열었는가 — 받은 사람이 할 수 없는 일을 안내하고 있었다</h2>
 * 결석 안내 알림톡은 <b>보호자에게만</b> 가는데(UM_8236) 문구가
 * <b>「사유가 있으시면 어플에서 사유 입력을 부탁드립니다」</b> 다. 그런데 지금까지
 * <b>학생만 낼 수 있었다</b>({@code requireStudent}).
 */
@SpringBootTest
@Transactional
class GuardianAbsenceReasonTest {

    @Autowired AbsenceReasonService absenceReasonService;
    @Autowired ApprovalService approvalService;
    @Autowired AppScopeResolver scopeResolver;
    @Autowired ApprovalRequestRepository requestRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    static final short YEAR = 2026;

    Academy academy;
    StudentEnrollment enrollment;
    Account motherAccount;
    Account fatherAccount;
    Student student;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        academy = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(academy);
        em.persist(new ApprovalItem(academy, YEAR,
                RequestType.ABSENCE_REASON, ApproverType.PARENT, null, null));

        student = new Student("GA-0001", "김사유", "010-1000-0001");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, YEAR, "2026-0001",
                null, GradeType.HIGH3);
        em.persist(enrollment);

        ParentGuardian mother = new ParentGuardian("어머니", "010-2000-0001", "F");
        em.persist(mother);
        em.persist(new StudentGuardianLink(student, mother, (short) 1, true));
        motherAccount = Account.forGuardian(mother, "GAPAR01", "x");
        em.persist(motherAccount);

        ParentGuardian father = new ParentGuardian("아버지", "010-2000-0002", "M");
        em.persist(father);
        em.persist(new StudentGuardianLink(student, father, (short) 2, false));
        fatherAccount = Account.forGuardian(father, "GAPAR02", "x");
        em.persist(fatherAccount);
        em.flush();
    }

    /**
     * ★★ 우선 승인자가 「부모」면 <b>제출 즉시 승인</b>이다.
     *
     * <p><b>낸 것이 곧 학부모 확인</b>이다(디랩 1031 §2 표). 승인을 또 받게 하면
     * <b>자기가 낸 것을 자기가 승인</b>하는 절차가 되어 한 단계가 형식만 남는다.
     */
    @Test
    @DisplayName("★★ 학부모가 내면 제출 즉시 승인된다 — 낸 것이 곧 학부모 확인이다")
    void guardianSubmissionIsApprovedImmediately() {
        var reason = absenceReasonService.submitByGuardian(enrollment, motherAccount,
                today, AbsenceReasonType.ABSENCE, "병원", null, null, null);
        em.flush();

        var request = requestRepository.findById(reason.getApprovalRequest().getId()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /**
     * ★★ <b>승인 요청 행을 「만들지 않는」 것이 아니다.</b>
     *
     * <p>만들고 <b>그 자리에서 승인</b>한다 — ⚠️ 그러지 않으면 승인 보드에 아무것도
     * 남지 않아 <b>「승인 요청이 왜 안 보이지」에 답할 수 없다</b>(프런트 지적).
     *
     * <p>★ 승인자는 <b>그 학부모</b>로 박힌다.
     */
    @Test
    @DisplayName("★★ 즉시 승인이어도 요청 행이 남는다 — 안 만들면 보드에 보여줄 게 없다")
    void immediateApprovalStillLeavesARow() {
        var reason = absenceReasonService.submitByGuardian(enrollment, motherAccount,
                today, AbsenceReasonType.ABSENCE, "병원", null, null, null);
        em.flush();

        var request = requestRepository.findById(reason.getApprovalRequest().getId()).orElseThrow();
        assertThat(request.getResolverAccount()).isNotNull();
        assertThat(request.getResolverAccount().getId())
                .as("★ 낸 학부모가 승인자로 박힌다")
                .isEqualTo(motherAccount.getId());
        assertThat(request.getResolvedAt()).isNotNull();
    }

    /**
     * ★★ 우선 승인자가 「담임」이면 <b>그대로 담임에게 간다</b> — 즉시 승인이 아니다.
     *
     * <p>⚠️ 학부모가 냈다고 늘 즉시 승인하면 <b>담임 확인을 건너뛴다.</b>
     */
    @Test
    @DisplayName("★★ 우선 승인자가 담임이면 즉시 승인하지 않는다 — 담임 확인을 건너뛰면 안 된다")
    void teacherPrimaryStaysPending() {
        approvalService.choosePrimaryApprover(enrollment, ApproverType.TEACHER, null,
                motherAccount);
        em.flush();

        var reason = absenceReasonService.submitByGuardian(enrollment, motherAccount,
                today, AbsenceReasonType.ABSENCE, "병원", null, null, null);
        em.flush();

        var request = requestRepository.findById(reason.getApprovalRequest().getId()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    /**
     * ★ <b>누가 냈는지</b>가 남는다 — 관리자 화면이 「학부모 신청」을 구분한다.
     */
    @Test
    @DisplayName("★ 누가 냈는지 남는다 — 관리자 화면이 「학부모 신청」을 구분한다")
    void recordsSubmitter() {
        var reason = absenceReasonService.submitByGuardian(enrollment, motherAccount,
                today, AbsenceReasonType.ABSENCE, "병원", null, null, null);
        em.flush();

        assertThat(reason.getSubmitterType()).isEqualTo(AbsenceSubmitterType.GUARDIAN);
        assertThat(reason.getSubmittedByAccount().getId()).isEqualTo(motherAccount.getId());
    }

    /**
     * ★★ <b>자녀가 아니면 거절한다.</b>
     *
     * <p>⚠️ {@code studentId} 를 요청으로 받으므로 <b>값을 바꿔 보낼 수 있다</b> —
     * 매번 확인하지 않으면 <b>남의 자녀 사유를 낼 수 있다.</b>
     */
    @Test
    @DisplayName("★★ 자녀가 아니면 거절한다 — studentId 를 바꿔 보낼 수 있다")
    void rejectsSomeoneElsesChild() {
        Student other = new Student("GA-0002", "남의자녀", "010-1000-0002");
        em.persist(other);
        em.persist(new StudentEnrollment(other, academy, YEAR, "2026-0002",
                null, GradeType.HIGH3));
        em.flush();

        assertThatThrownBy(() -> scopeResolver.requireGuardian(
                motherAccount.getId(), other.getId(), "사유 신청"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * ★ <b>학생 계정은 학부모 경로를 쓸 수 없다.</b>
     */
    @Test
    @DisplayName("★ 학생 계정은 학부모 경로를 쓸 수 없다")
    void studentCannotUseGuardianPath() {
        Account studentAccount = Account.forStudent(student, "GASTU01", "x");
        em.persist(studentAccount);
        em.flush();

        assertThatThrownBy(() -> scopeResolver.requireGuardian(
                studentAccount.getId(), student.getId(), "사유 신청"))
                .isInstanceOf(BusinessException.class);
    }
}
