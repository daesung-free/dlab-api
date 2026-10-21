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

    // ─────────────────────────────────────────────────────────────
    // 일괄 취소 · 수정 (디랩 1021 피드백 2-7 · 2-8)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 일괄 취소 — 한 건이 실패해도 나머지는 취소된다")
    void bulkRevokeKeepsGoingOnFailure() {
        var granted = penaltyService.grantManually(
                admin, List.of(minji.getId(), seojun.getId()), late, null, null);
        em.flush();
        var ids = granted.stream().map(PenaltyPoint::getId).toList();

        // 하나를 먼저 취소해 둔다 — 일괄에서 그 건이 실패한다
        penaltyService.revoke(admin, ids.get(0));
        em.flush();

        var result = penaltyService.revokeAll(admin,
                List.of(ids.get(0), ids.get(1), 999_999L));
        em.flush();

        // ★ 20건을 체크했는데 하나가 걸려 전부 되돌아가면 담당자는 어느 것이
        //   걸렸는지 모르는 채 다시 20건을 고르게 된다
        assertThat(result.revoked()).containsExactly(ids.get(1));
        assertThat(result.failed()).containsOnlyKeys(ids.get(0), 999_999L);
        // ⚠️ 사유가 함께 와야 한다 — 「2건 실패」만 보여주면 담당자가 할 일이 없다
        assertThat(result.failed().values()).allSatisfy(m -> assertThat(m).isNotBlank());
    }

    @Test
    @DisplayName("★ 빈 목록으로 부르면 아무것도 안 바뀐다")
    void bulkRevokeWithEmptyList() {
        var result = penaltyService.revokeAll(admin, List.of());
        assertThat(result.revoked()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("★ 일자·사유를 고친다 — 취소 후 재입력이 유일한 길이었다")
    void modifyChangesDateAndReason() {
        var granted = penaltyService.grantManually(
                admin, List.of(minji.getId()), late, "원래 사유", null);
        em.flush();
        Long id = granted.get(0).getId();

        var target = java.time.LocalDate.now(clock).minusDays(3);
        penaltyService.modify(admin, id, target, "고친 사유");
        em.flush();
        em.clear();

        var after = em.find(PenaltyPoint.class, id);
        assertThat(after.getReason()).isEqualTo("고친 사유");
        assertThat(after.getOccurredAt().atZone(clock.getZone()).toLocalDate())
                .isEqualTo(target);
    }

    @Test
    @DisplayName("★ 비워 보낸 항목은 바꾸지 않는다")
    void modifyKeepsOmittedFields() {
        var granted = penaltyService.grantManually(
                admin, List.of(minji.getId()), late, "원래 사유", null);
        em.flush();
        Long id = granted.get(0).getId();
        // ★★ before 를 DB 에서 읽는다 — flush 직후 em.find 는 영속성 컨텍스트의
        //    Java Instant(나노초)를 돌려주는데, 뒤에서 비교하는 값은 DB 에서 다시 읽어
        //    마이크로초로 잘린다. macOS 는 Instant.now() 가 마이크로초라 우연히 같고
        //    CI(Linux)는 나노초라 달라진다 — 실제로 CI 에서만 깨졌다.
        em.clear();
        var before = em.find(PenaltyPoint.class, id).getOccurredAt();

        penaltyService.modify(admin, id, null, "사유만 고침");
        em.flush();
        em.clear();

        var after = em.find(PenaltyPoint.class, id);
        assertThat(after.getReason()).isEqualTo("사유만 고침");
        assertThat(after.getOccurredAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("★★ 점수와 항목은 수정 경로에 없다 — 부여 시점 값이 흔들리면 안 된다")
    void modifyCannotTouchPointsOrItem() throws Exception {
        // 점수는 부여 시점의 항목 점수를 복사해 둔 값이다 — 항목 점수를 나중에 바꿔도
        // 과거 이력이 소급해서 바뀌지 않게 하기 위해서다. 수정 메서드가 그걸 열면
        // 그 보장이 사라진다. 시그니처로 못박는다.
        var modify = PenaltyPoint.class.getMethod(
                "modify", java.time.Instant.class, String.class);
        assertThat(modify.getParameterCount())
                .as("modify 가 점수·항목을 받으면 안 된다 — 항목을 바꿔야 하면 "
                        + "취소 후 재입력이 맞다(멱등키도 그 조합으로 잡혀 있다)")
                .isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────
    // 학생 단위 누적 집계 (디랩 1021 피드백 2-5)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 상벌점이 없는 학생도 0으로 나온다 — 집계만 내리면 목록에서 사라진다")
    void summaryIncludesStudentsWithoutPoints() {
        // 민지에게만 준다. 서준은 한 건도 없다
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        em.flush();

        var rows = penaltyService.summaryByStudent(admin, bundang.getId(), minji.getYear());

        // ★ 집계 쿼리는 그룹이 안 생겨 서준을 안 돌려준다 — 재원생 기준으로 채워야 한다
        assertThat(rows).extracting(
                        PenaltyService.StudentPenaltySummary::enrollmentId)
                .contains(minji.getId(), seojun.getId());
        var seojunRow = rows.stream()
                .filter(r -> r.enrollmentId().equals(seojun.getId()))
                .findFirst().orElseThrow();
        assertThat(seojunRow.merit()).isZero();
        assertThat(seojunRow.demerit()).isZero();
        assertThat(seojunRow.total()).isZero();
    }

    @Test
    @DisplayName("★★ 상점 합과 벌점 합이 따로 온다 — 합계만 내면 169/152 와 0/0 이 같아 보인다")
    void summarySplitsMeritAndDemerit() {
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null, null);
        penaltyService.grantManually(admin, List.of(minji.getId()), perfect, null, null);
        em.flush();

        var row = penaltyService.summaryByStudent(admin, bundang.getId(), minji.getYear()).stream()
                .filter(r -> r.enrollmentId().equals(minji.getId()))
                .findFirst().orElseThrow();

        // ★ 벌점은 음수로 저장되지만 절댓값으로 온다 — 음수를 내보내면
        //   「벌점 높은 순」 정렬이 뒤집힌다
        assertThat(row.demerit()).isPositive();
        assertThat(row.merit()).isPositive();
        assertThat(row.total()).isEqualTo(row.merit() - row.demerit());
    }

    @Test
    @DisplayName("★ 취소한 건은 집계에서 빠진다")
    void revokedPointsAreExcludedFromSummary() {
        var granted = penaltyService.grantManually(
                admin, List.of(minji.getId()), late, null, null);
        em.flush();
        penaltyService.revoke(admin, granted.get(0).getId());
        em.flush();

        var row = penaltyService.summaryByStudent(admin, bundang.getId(), minji.getYear()).stream()
                .filter(r -> r.enrollmentId().equals(minji.getId()))
                .findFirst().orElseThrow();
        assertThat(row.demerit()).isZero();
    }

    @Test
    @DisplayName("★ 기간을 걸지 않는다 — 제적 기준 40점이 누적이다")
    void summaryIsCumulativeNotPeriodic() {
        // 한참 전 날짜로 부여한다. 기간 필터가 있으면 이 건이 빠진다
        penaltyService.grantManually(admin, List.of(minji.getId()), late, null,
                java.time.LocalDate.now(clock).minusMonths(8));
        em.flush();

        var row = penaltyService.summaryByStudent(admin, bundang.getId(), minji.getYear()).stream()
                .filter(r -> r.enrollmentId().equals(minji.getId()))
                .findFirst().orElseThrow();
        assertThat(row.demerit())
                .as("8개월 전 벌점이 빠지면 임계(40점)에 가까운 학생이 드러나지 않는다")
                .isPositive();
    }
}
