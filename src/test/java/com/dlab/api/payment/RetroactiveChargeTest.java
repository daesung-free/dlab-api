package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.service.RetroactiveChargeService;
import com.dlab.domain.payment.service.TuitionBillingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 할인 소급 재결제 (0820 규정 · 판정 2026-08-26 확정).
 *
 * <p><b>규정 예시 두 개를 그대로 재현하는지</b>가 이 테스트의 목적이다.
 * 판정이 뒤집히면 학생에게 안 받아야 할 돈을 받거나, 받아야 할 돈을 못 받는다.
 */
@SpringBootTest
@Transactional
class RetroactiveChargeTest {

    @Autowired RetroactiveChargeService chargeService;
    @Autowired TuitionBillingService billingService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2096;

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("R1", "소급테스트", LocalTime.of(9, 0));
        em.persist(academy);

        Student student = new Student("RC-0001", "김소급", "010-0000-0000");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, YEAR, "2096-0001", null,
                GradeType.N_SU);
        em.persist(enrollment);

        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        for (int m = 3; m <= 6; m++) {
            em.persist(TuitionMonth.common(YEAR, m, 30));
        }
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    /** 그 달 청구를 만든다. {@code discountRate}가 0이면 정상가다. */
    private void issue(int month, int discountRate) {
        billingService.issueMonthly(admin, enrollment.getId(), YearMonth.of(YEAR, month),
                SeatType.GENERAL, discountRate, null, null);
    }

    // ─────────────────────────────────────────── 규정 예시 재현

    @Test
    @DisplayName("★★ 규정 예시 1 — 3~5월 할인 + 6월 정상가 연장 → 6월 퇴원 시 소급 없음")
    void noChargeWhenWithdrawnAfterDiscountEnded() {
        issue(3, 50);
        issue(4, 50);
        issue(5, 50);
        issue(6, 0);        // 정상가로 연장
        em.flush();

        var preview = chargeService.preview(admin, enrollment.getId(),
                LocalDate.of(YEAR, 6, 20));

        assertThat(preview.hasCharge()).isFalse();
        assertThat(preview.reason()).contains("정상가");
    }

    @Test
    @DisplayName("★★ 규정 예시 2 — 3~5월 할인 → 5월 퇴원 시 이전 할인분을 소급")
    void chargesWhenWithdrawnInsideDiscountPeriod() {
        issue(3, 50);
        issue(4, 50);
        issue(5, 50);
        em.flush();

        var preview = chargeService.preview(admin, enrollment.getId(),
                LocalDate.of(YEAR, 5, 20));

        // ★ 3·4월만이다. 5월은 환불 계산이 처리하므로 여기 들어오면 두 번 받게 된다
        assertThat(preview.targets()).hasSize(2);
        assertThat(preview.totalAmount()).isEqualTo(660_000);   // 330,000 × 2
    }

    @Test
    @DisplayName("★ 퇴원한 달은 소급 대상이 아니다 — 환불 계산이 이미 처리한다")
    void withdrawalMonthIsExcluded() {
        issue(3, 50);
        issue(4, 50);
        em.flush();

        var preview = chargeService.preview(admin, enrollment.getId(),
                LocalDate.of(YEAR, 4, 10));

        assertThat(preview.targets()).singleElement()
                .satisfies(t -> assertThat(t.billing().getServiceMonth()).isEqualTo((short) 3));
    }

    // ─────────────────────────────────────────── 발행

    @Test
    @DisplayName("소급 청구가 발행되고 달별 내역이 남는다")
    void issuesChargeWithRows() {
        issue(3, 50);
        issue(4, 30);
        issue(5, 50);
        em.flush();

        var result = chargeService.charge(admin, enrollment.getId(),
                LocalDate.of(YEAR, 5, 20), null);

        // 3월 330,000 + 4월 198,000 = 528,000
        assertThat(result.totalAmount()).isEqualTo(528_000);
        assertThat(result.billing().getBilledAmount()).isEqualTo(528_000);
        assertThat(result.rows()).hasSize(2)
                .extracting(RetroactiveCharge::getChargeAmount)
                .containsExactly(330_000, 198_000);
    }

    @Test
    @DisplayName("★ 소급 청구에는 이용 월이 없다 — 달을 지정하면 그 달 교습비와 중복으로 잡힌다")
    void chargeBillingHasNoServiceMonth() {
        issue(3, 50);
        issue(4, 50);
        em.flush();

        var result = chargeService.charge(admin, enrollment.getId(),
                LocalDate.of(YEAR, 4, 20), null);

        assertThat(result.billing().getServiceYear()).isNull();
        assertThat(result.billing().getServiceMonth()).isNull();
    }

    @Test
    @DisplayName("★ 대상이 없으면 0원 청구를 만들지 않는다 — 미납자 화면이 지저분해진다")
    void doesNotIssueEmptyBilling() {
        issue(3, 0);
        issue(4, 0);
        em.flush();

        var result = chargeService.charge(admin, enrollment.getId(),
                LocalDate.of(YEAR, 4, 20), null);

        assertThat(result.billing()).isNull();
        assertThat(result.rows()).isEmpty();
    }

    @Test
    @DisplayName("★ 같은 청구를 두 번 소급하지 않는다 — 학생이 두 번 낸다")
    void doesNotChargeTwice() {
        issue(3, 50);
        issue(4, 50);
        issue(5, 50);
        em.flush();

        chargeService.charge(admin, enrollment.getId(), LocalDate.of(YEAR, 5, 20), null);
        em.flush();

        // 다시 돌려도 남은 대상이 없다
        var second = chargeService.charge(admin, enrollment.getId(),
                LocalDate.of(YEAR, 5, 20), null);

        assertThat(second.billing()).isNull();
    }

    // ─────────────────────────────────────────── 판정 근거

    @Test
    @DisplayName("★ 퇴원한 달 청구가 없으면 판정하지 않는다 — 임의로 소급하면 안 받을 돈을 받는다")
    void noJudgementWithoutWithdrawalMonthBilling() {
        issue(3, 50);
        issue(4, 50);
        em.flush();

        var preview = chargeService.preview(admin, enrollment.getId(),
                LocalDate.of(YEAR, 6, 20));   // 6월 청구가 없다

        assertThat(preview.hasCharge()).isFalse();
        assertThat(preview.reason()).contains("판정할 수 없습니다");
    }

    @Test
    @DisplayName("대상이 없을 때도 이유가 남는다 — '소급 없음'만 뜨면 맞는지 확인할 방법이 없다")
    void reasonIsAlwaysPresent() {
        issue(5, 0);
        em.flush();

        var preview = chargeService.preview(admin, enrollment.getId(),
                LocalDate.of(YEAR, 5, 20));

        assertThat(preview.reason()).isNotBlank();
    }

    @Test
    @DisplayName("★ 독서실비는 소급 대상이 아니다 — 규정상 할인이 없다")
    void studyRoomIsNotCharged() {
        issue(3, 50);
        issue(4, 50);
        em.flush();

        var preview = chargeService.preview(admin, enrollment.getId(),
                LocalDate.of(YEAR, 4, 20));

        // 3월 교습비 정가 660,000 − 납부 330,000 = 330,000.
        // 독서실비 90,000은 할인이 없어 들어가지 않는다
        assertThat(preview.totalAmount()).isEqualTo(330_000);
    }
}
