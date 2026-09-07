package com.dlab.api.penalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.service.PenaltyService;
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
 * 상벌점 관리 (F-4.1-2).
 *
 * <p>DSA에는 수기 일괄 부여만 있었다. 자동 규칙엔진은 I-5 확정 전까지 돌지 않는다.
 */
@SpringBootTest
@Transactional
class AdminPenaltyBoardTest {

    @Autowired PenaltyService penaltyService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    StudentEnrollment seojun;
    PenaltyItem late;      // 벌점 2
    PenaltyItem perfect;   // 상점 3
    AuthPrincipal admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        minji = enroll("DL-1", "김민지", "2026-0001");
        seojun = enroll("DL-2", "박서준", "2026-0002");

        late = new PenaltyItem(bundang, (short) 2026, "지각", 2, PenaltyCategory.DEMERIT);
        em.persist(late);
        perfect = new PenaltyItem(bundang, (short) 2026, "데일리테스트 만점", 3, PenaltyCategory.MERIT);
        em.persist(perfect);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, bundang, (short) 2026, stdNo, null, GradeType.HIGH3);
        em.persist(e);
        return e;
    }

    private PenaltyService.PenaltyBoard board() {
        em.flush();
        em.clear();
        return penaltyService.board(admin, null, today, today, null, null, null, null, null);
    }

    @Test
    @DisplayName("★ 선택 일괄 부여 — 여러 명에게 한 번에 준다")
    void grantsToMultipleStudentsAtOnce() {
        List<PenaltyPoint> granted = penaltyService.grantManually(
                admin, List.of(minji.getId(), seojun.getId()), late, "지각 확인", null);

        assertThat(granted).hasSize(2);
        assertThat(board().rows()).hasSize(2);
    }

    @Test
    @DisplayName("★ 점수는 항목 값 그대로 복사된다 — 나중에 항목을 바꿔도 과거가 안 변한다")
    void pointIsCopiedFromItemAtGrantTime() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        em.flush();

        // 벌점은 음수로 저장된다 — 항목에 2로 넣어도 -2다. 통계가 부호로 상점·벌점을 가른다
        assertThat(board().rows()).first()
                .satisfies(p -> assertThat(p.getPoints()).isEqualTo(-2));
    }

    @Test
    @DisplayName("사유를 안 적으면 항목명이 들어간다")
    void reasonDefaultsToItemName() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);

        assertThat(board().rows()).first()
                .satisfies(p -> assertThat(p.getReason()).isEqualTo("지각"));
    }

    @Test
    @DisplayName("★ 합계는 조회 조건 기준이다 — 필터를 걸면 같이 줄어야 한다")
    void summaryFollowsFilter() {
        penaltyService.grantManually(admin, List.of(minji.getId(), seojun.getId()), late, null, null);
        penaltyService.grantManually(admin, List.of(minji.getId()), perfect, null, null);
        em.flush();
        em.clear();

        var all = penaltyService.board(admin, null, today, today, null, null, null, null, null);
        assertThat(all.plusTotal()).isEqualTo(3);
        assertThat(all.minusTotal()).isEqualTo(-4);   // 벌점은 음수로 표시

        var demeritOnly = penaltyService.board(admin, null, today, today, PenaltyCategory.DEMERIT, null, null, null, null);
        assertThat(demeritOnly.rows()).hasSize(2);
        assertThat(demeritOnly.plusTotal()).isZero();
    }

    @Test
    @DisplayName("★ 벌점 합계는 음수로 내린다 — 화면이 카테고리를 다시 안 본다")
    void demeritTotalIsNegative() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);

        assertThat(board().minusTotal()).isEqualTo(-2);
    }

    @Test
    @DisplayName("이름·학번으로 거른다")
    void filtersByKeyword() {
        penaltyService.grantManually(admin, List.of(minji.getId(), seojun.getId()), late, null, null);
        em.flush();
        em.clear();

        assertThat(penaltyService.board(admin, null, today, today, null, null, null, "김민지", null).rows())
                .hasSize(1);
        assertThat(penaltyService.board(admin, null, today, today, null, null, null, "2026-0002", null).rows())
                .hasSize(1);
    }

    @Test
    @DisplayName("부여 방식으로 거른다 — 자동 규칙은 I-5 확정 전까지 0건이다")
    void filtersBySource() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        em.flush();
        em.clear();

        assertThat(penaltyService.board(admin, null, today, today, null, List.of(PenaltySource.MANUAL), null, null, null).rows()).hasSize(1);
        assertThat(penaltyService.board(admin, null, today, today, null, List.of(PenaltySource.KIOSK), null, null, null).rows()).isEmpty();
        assertThat(penaltyService.board(admin, null, today, today, null, null, null, null, null).autoCount())
                .isZero();
    }

    @Test
    @DisplayName("★ 취소는 soft delete — 누가 왜 취소했는지 추적이 끊기면 안 된다")
    void revokeIsSoftDelete() {
        PenaltyPoint granted = penaltyService
                .grantManually(admin, List.of(minji.getId()), late, null, null).get(0);
        em.flush();

        penaltyService.revoke(admin, granted.getId());
        em.flush();

        assertThat(board().rows()).isEmpty();
        // 행 자체는 남아 있다
        assertThat(em.find(PenaltyPoint.class, granted.getId())).isNotNull();
    }

    @Test
    @DisplayName("★★ source 를 여러 개 받는다 — 화면의 '자동'이 KIOSK+ROUTINE 두 값의 OR라 단일값으론 못 보낸다")
    void sourceAcceptsMultipleValues() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        em.flush();

        assertThat(penaltyService.board(admin, null, today, today, null,
                List.of(PenaltySource.KIOSK, PenaltySource.ROUTINE), null, null, null).rows())
                .isEmpty();
        assertThat(penaltyService.board(admin, null, today, today, null,
                List.of(PenaltySource.KIOSK, PenaltySource.MANUAL), null, null, null).rows())
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 발생일을 지정할 수 있다 — 어제 일을 오늘 넣는 경우가 실제로 있다")
    void grantAcceptsOccurredDate() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, today.minusDays(1));
        em.flush();

        // 오늘 조회에는 안 나오고
        assertThat(board().rows()).isEmpty();
        // 어제 조회에 나온다
        assertThat(penaltyService.board(admin, null, today.minusDays(1), today.minusDays(1),
                null, null, null, null, null).rows()).hasSize(1);
    }

    @Test
    @DisplayName("★ 미래 일자로는 부여할 수 없다")
    void futureGrantIsRejected() {
        assertThatThrownBy(() -> penaltyService.grantManually(
                admin, List.of(minji.getId()), late, null, today.plusDays(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 재원 상태로 거른다 — 화면 조건이 전체/재원생/퇴원생 3종이다")
    void filtersByEnrollmentStatus() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        em.flush();

        assertThat(penaltyService.board(admin, null, today, today, null, null,
                com.dlab.domain.user.entity.EnrollmentStatus.ENROLLED, null, null).rows())
                .hasSize(1);
        assertThat(penaltyService.board(admin, null, today, today, null, null,
                com.dlab.domain.user.entity.EnrollmentStatus.WITHDRAWN, null, null).rows())
                .isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 학생에게는 부여할 수 없다")
    void cannotGrantToOtherAcademyStudent() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> penaltyService.grantManually(
                ilsanAdmin, List.of(minji.getId()), late, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 오늘 부여분이 오늘 조회에 나온다 — 끝 날짜가 빠지면 확인이 안 된다")
    void todayGrantAppearsInTodayRange() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);

        assertThat(board().rows()).hasSize(1);
    }

    // ── 지점 스코프 ────────────────────────────────────────────
    // academyScopeFilter()의 null은 "필터 없음"이지 "지점 모름"이 아니다.

    @Test
    @DisplayName("★ 본사가 지점을 고르면 그 지점 상벌점이 보인다 — 예전엔 400이라 화면이 안 열렸다")
    void headOfficeSeesPickedAcademy() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        em.flush();
        em.clear();
        AuthPrincipal headOffice = AuthPrincipal.of(9L, "EMPLOYEE", null,
                List.of(Role.SUPER_ADMIN), true);

        assertThat(penaltyService.board(headOffice, bundang.getId(), today, today, null, null, null, null, null).rows()).hasSize(1);
    }

    @Test
    @DisplayName("본사가 지점을 안 고르면 400 — 전 지점 상벌점을 섞어 뿌리지 않는다")
    void headOfficeMustPickAcademy() {
        AuthPrincipal headOffice = AuthPrincipal.of(9L, "EMPLOYEE", null,
                List.of(Role.SUPER_ADMIN), true);

        assertThatThrownBy(() -> penaltyService.board(headOffice, null, today, today, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("지점 관리자는 안 골라도 자기 지점이 보인다")
    void branchAdminDefaultsToOwnAcademy() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);

        assertThat(board().rows()).hasSize(1);
    }

    @Test
    @DisplayName("★ 지점 관리자가 다른 지점을 지정하면 거부된다 — 요청 값을 그대로 믿지 않는다")
    void branchAdminCannotPickOtherAcademy() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        assertThatThrownBy(() -> penaltyService.board(admin, ilsan.getId(), today, today, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }
}
