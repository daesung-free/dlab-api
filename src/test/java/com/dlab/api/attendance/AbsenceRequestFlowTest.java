package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.service.AbsenceReasonService;
import com.dlab.domain.attendance.service.AbsenceReasonService.AbsenceRequestRow;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사유 신청 관리 (F-4.1-6).
 *
 * <p>승인·반려는 여기서 검증하지 않는다 — {@code FirewallApprovalFlowTest}가
 * 같은 승인 엔진을 이미 검증한다. 여기 핵심은 <b>등록이 승인 라우팅을 타는지</b>다.
 */
@SpringBootTest
@Transactional
class AbsenceRequestFlowTest {

    @Autowired AbsenceReasonService absenceReasonService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        // 승인 정책이 없으면 등록 자체가 안 된다 — 라우팅이 여기서 나온다
        em.persist(new ApprovalItem(bundang, (short) 2026,
                RequestType.ABSENCE_REASON, ApproverType.PARENT, null, null));

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private AbsenceRequestRow register(AbsenceReasonType type, LocalTime start, LocalTime end) {
        absenceReasonService.register(admin, minji.getId(), today, type, "병원", start, end);
        em.flush();
        em.clear();
        return absenceReasonService.list(admin, null, today, today, null).get(0);
    }

    @Test
    @DisplayName("★ 관리자가 등록해도 승인 라우팅을 탄다 — 자동 승인이 아니다")
    void adminRegistrationStillGoesThroughApproval() {
        AbsenceRequestRow row = register(AbsenceReasonType.ABSENCE, null, null);

        assertThat(row.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(row.approverType()).isEqualTo(ApproverType.PARENT);
        // 화면이 이 id로 /admin/approvals를 부른다
        assertThat(row.approvalRequestId()).isNotNull();
    }

    @Test
    @DisplayName("기간 표기는 서버가 만든다 — 화면마다 조립하면 표기가 갈린다")
    void periodLabelIsBuiltOnServer() {
        assertThat(register(AbsenceReasonType.ABSENCE, null, null).period()).isEqualTo("종일");
        em.clear();
    }

    @Test
    @DisplayName("외출은 시작 ~ 종료로 표시된다")
    void outingShowsRange() {
        AbsenceRequestRow row = register(
                AbsenceReasonType.OUTING, LocalTime.of(13, 0), LocalTime.of(15, 0));

        assertThat(row.period()).isEqualTo("13:00 ~ 15:00");
    }

    @Test
    @DisplayName("조퇴는 '시작 이후'로 표시된다 — 복귀가 없다")
    void earlyLeaveShowsFromOnly() {
        AbsenceRequestRow row = register(AbsenceReasonType.EARLY_LEAVE, LocalTime.of(16, 30), null);

        assertThat(row.period()).isEqualTo("16:30 이후");
    }

    @Test
    @DisplayName("★ 외출에 종료가 없으면 거부 — 언제 돌아오는지 알 수 없으면 복귀와 대조가 안 된다")
    void outingWithoutEndIsRejected() {
        assertThatThrownBy(() -> absenceReasonService.register(
                admin, minji.getId(), today, AbsenceReasonType.OUTING,
                "병원", LocalTime.of(13, 0), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("종료");
    }

    @Test
    @DisplayName("종료가 시작보다 빠르면 거부")
    void reversedRangeIsRejected() {
        assertThatThrownBy(() -> absenceReasonService.register(
                admin, minji.getId(), today, AbsenceReasonType.OUTING,
                "병원", LocalTime.of(15, 0), LocalTime.of(13, 0)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 다른 지점 학생은 등록할 수 없다")
    void otherAcademyStudentIsRejected() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> absenceReasonService.register(
                ilsanAdmin, minji.getId(), today, AbsenceReasonType.ABSENCE, "사유", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상태 탭으로 거른다")
    void filtersByStatus() {
        register(AbsenceReasonType.ABSENCE, null, null);

        assertThat(absenceReasonService.list(admin, null, today, today, ApprovalStatus.PENDING)).hasSize(1);
        assertThat(absenceReasonService.list(admin, null, today, today, ApprovalStatus.APPROVED)).isEmpty();
    }

    @Test
    @DisplayName("★ 통계에 벌점 충돌은 아직 안 센다 — I-10 확정 전이라 0이 '없음'으로 읽히면 안 된다")
    void summaryMarksPenaltyConflictUnavailable() {
        register(AbsenceReasonType.ABSENCE, null, null);

        var summary = absenceReasonService.summary(admin, null, today, today);

        assertThat(summary.get("pending")).isEqualTo(1);
        assertThat(summary.get("waitingParent")).isEqualTo(1);
        assertThat(summary).containsKey("penaltyConflictUnavailable");
        assertThat(summary).doesNotContainKey("penaltyConflict");
    }
}
