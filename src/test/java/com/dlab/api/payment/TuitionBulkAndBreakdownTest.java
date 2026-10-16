package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionMonth;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.TuitionBillingService;
import com.dlab.domain.payment.service.TuitionBillingService.BulkTarget;
import com.dlab.domain.payment.service.TuitionPricingService;
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

/**
 * 교습비 <b>일괄 청구</b>와 <b>일할 계산 근거</b> (1016 피드백 2-1 · 2-4 · 2-5 · 2-6).
 *
 * <h2>⚠️ 여기는 {@code @Transactional} 을 붙이지 않는다</h2>
 * 일괄 발행이 <b>건마다 트랜잭션을 분리</b>하는 것이 이 기능의 핵심이다. 테스트를
 * 한 트랜잭션으로 감싸면 그 분리가 작동하는지 확인할 수 없고, <b>실패 뒤 성공</b>이
 * 되는지도 알 수 없다 — 운영에서 깨질 바로 그 지점이 테스트에서 가려진다.
 * 그래서 쓴 데이터는 뒤에서 직접 지운다.
 */
@SpringBootTest
class TuitionBulkAndBreakdownTest {

    @Autowired TuitionBillingService billingService;
    @Autowired TuitionPricingService pricingService;
    @Autowired EntityManager em;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2097;
    static final YearMonth MONTH = YearMonth.of(YEAR, 3);

    Academy academy;
    StudentEnrollment a;
    StudentEnrollment b;
    StudentEnrollment noPrice;
    AuthPrincipal admin;

    /** ⚠️ 트랜잭션으로 감싸지 않으니 지점이 남는다 — 테스트마다 다른 코드를 쓴다. */
    static int seq = 0;

    @BeforeEach
    void setUp() {
        purge();
        tx.executeWithoutResult(status -> {
            academy = new Academy("BK%d".formatted(++seq), "일괄청구테스트", LocalTime.of(9, 0));
            em.persist(academy);

            a = enrollment("BK-%d1".formatted(seq), "학생가", "2097-%d1".formatted(seq),
                    GradeType.N_SU);
            b = enrollment("BK-%d2".formatted(seq), "학생나", "2097-%d2".formatted(seq),
                    GradeType.N_SU);
            // ★ 가격이 없는 학년 — 일괄에서 「한 건만」 실패해야 한다
            noPrice = enrollment("BK-%d3".formatted(seq), "학생다", "2097-%d3".formatted(seq),
                    GradeType.HIGH2);

            em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL,
                    660_000, 90_000));
            em.persist(TuitionMonth.common(YEAR, 3, 27));   // 설 하루 제외 가정
            em.flush();
        });
        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    private StudentEnrollment enrollment(String code, String name, String no, GradeType grade) {
        Student s = new Student(code, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, academy, YEAR, no, null, grade);
        em.persist(e);
        return e;
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        purge();
    }

    /**
     * 이 테스트가 쓴 것만 지운다.
     *
     * <p>★ <b>시작할 때도 돌린다.</b> 앞선 실행이 중간에 깨졌으면 지점·학생이 남아
     * 다음 실행이 유니크 제약으로 또 깨진다 — <b>테스트가 앞선 실행 상태에 의존하면
     * 한 번 깨진 뒤로 계속 깨진다.</b>
     */
    private void purge() {
        tx.executeWithoutResult(status -> {
            em.createQuery("DELETE FROM BillingItem i WHERE i.billing.id IN "
                    + "(SELECT bb.id FROM Billing bb WHERE bb.year = :y)")
                    .setParameter("y", YEAR).executeUpdate();
            em.createQuery("DELETE FROM Billing b WHERE b.year = :y")
                    .setParameter("y", YEAR).executeUpdate();
            em.createQuery("DELETE FROM StudentEnrollment e WHERE e.year = :y")
                    .setParameter("y", YEAR).executeUpdate();
            em.createQuery("DELETE FROM TuitionPrice p WHERE p.year = :y")
                    .setParameter("y", YEAR).executeUpdate();
            em.createQuery("DELETE FROM TuitionMonth m WHERE m.year = :y")
                    .setParameter("y", YEAR).executeUpdate();
            em.createQuery("DELETE FROM Student s WHERE s.uniqueCode LIKE 'BK-%'")
                    .executeUpdate();
            em.createQuery("DELETE FROM Academy a WHERE a.acadCd LIKE 'BK%'")
                    .executeUpdate();
        });
    }

    // ─────────────────────────────────────────── 2-1 일괄

    @Test
    @DisplayName("★★ 한 건이 실패해도 나머지는 발행된다 — 그리고 실패 사유가 건별로 나온다")
    void oneFailureDoesNotBlockTheRest() {
        var result = billingService.issueMonthlyBulk(admin, MONTH, LocalDate.of(YEAR, 3, 31),
                List.of(new BulkTarget(a.getId(), SeatType.GENERAL, 0, null, null, null),
                        // 가격 미등록 — 이 건만 실패해야 한다
                        new BulkTarget(noPrice.getId(), SeatType.GENERAL, 0, null, null, null),
                        new BulkTarget(b.getId(), SeatType.GENERAL, 50, null, null, null)));

        // ★ 실패가 「그 뒤 전부 실패」로 번지지 않아야 한다 — 실패 다음 건이 발행됐는지가 핵심
        assertThat(result.issued()).hasSize(2);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).enrollmentId()).isEqualTo(noPrice.getId());
        assertThat(result.failed().get(0).reason()).isNotBlank();

        assertThat(result.issued()).extracting(Billing::getBilledAmount)
                .containsExactly(750_000, 420_000);   // 할인율이 행마다 다르다
    }

    @Test
    @DisplayName("★ 이미 청구가 있으면 그 건만 실패한다 — 두 번 눌러도 중복 발행되지 않는다")
    void duplicateIsReportedPerRow() {
        billingService.issueMonthlyBulk(admin, MONTH, null,
                List.of(new BulkTarget(a.getId(), SeatType.GENERAL, 0, null, null, null)));

        var again = billingService.issueMonthlyBulk(admin, MONTH, null,
                List.of(new BulkTarget(a.getId(), SeatType.GENERAL, 0, null, null, null),
                        new BulkTarget(b.getId(), SeatType.GENERAL, 0, null, null, null)));

        assertThat(again.issued()).hasSize(1);        // 나는 새로 발행된다
        assertThat(again.failed()).hasSize(1);
        assertThat(again.failed().get(0).reason()).contains("이미");
    }

    // ─────────────────────────────────────────── 2-4 계산 근거

    @Test
    @DisplayName("★★ 근거의 청구액이 실제 발행 금액과 같다 — 두 경로가 어긋나면 데스크가 틀렸다고 읽는다")
    void breakdownMatchesIssuedAmount() {
        TuitionPrice price = pricingService.price(YEAR, GradeType.N_SU, SeatType.GENERAL,
                academy.getId());
        var breakdown = pricingService.breakdown(price, YEAR, 3, academy.getId(), 50, 12);

        Billing billing = billingService.issueMonthly(admin, a.getId(), MONTH,
                SeatType.GENERAL, 50, 12, null);

        // 미리보기와 발행이 다른 값을 내면 미리보기가 거짓말을 한다
        assertThat(breakdown.billedAmount()).isEqualTo(billing.getBilledAmount());
        assertThat(breakdown.suppliedTotal()).isEqualTo(billing.getSuppliedAmount());
        assertThat(breakdown.discountAmount()).isEqualTo(billing.getDiscountAmount());
    }

    @Test
    @DisplayName("★ 월 전체 청구는 1일 단가를 내리지 않는다 — 정액이라 단가 × 일수와 값이 다르다")
    void wholeMonthOmitsDailyRate() {
        TuitionPrice price = pricingService.price(YEAR, GradeType.N_SU, SeatType.GENERAL,
                academy.getId());

        var whole = pricingService.breakdown(price, YEAR, 3, academy.getId(), 0, null);
        assertThat(whole.wholeMonth()).isTrue();
        // 단가를 내려주면 화면이 "660,000 ÷ 27 × 27" 로 적고, 그 값이 청구액과 달라진다
        assertThat(whole.dailyTuition()).isNull();
        assertThat(whole.billedAmount()).isEqualTo(750_000);

        var partial = pricingService.breakdown(price, YEAR, 3, academy.getId(), 0, 12);
        assertThat(partial.wholeMonth()).isFalse();
        assertThat(partial.dailyTuition()).isEqualTo(660_000 / 27);     // 버림
        assertThat(partial.rounding()).isEqualTo("FLOOR");
    }

    // ─────────────────────────────────────────── 2-5 · 2-6

    @Test
    @DisplayName("★ 할인 근거와 대상 기간이 저장된다 — 월 전체는 1일~말일")
    void discountSourceAndWholeMonthPeriodAreStored() {
        Billing billing = billingService.issueMonthly(admin, a.getId(), MONTH,
                SeatType.GENERAL, 50, null, null, "수능 100%", null);

        assertThat(billing.getDiscountSource()).isEqualTo("수능 100%");
        assertThat(billing.getServiceFrom()).isEqualTo(LocalDate.of(YEAR, 3, 1));
        assertThat(billing.getServiceTo()).isEqualTo(LocalDate.of(YEAR, 3, 31));
        assertThat(billing.coversWholeMonth()).isTrue();
    }

    @Test
    @DisplayName("★★ 일할인데 시작일을 모르면 기간을 비워 둔다 — 교습일수로 역산하면 어긋난다")
    void proratedWithoutStartDateLeavesPeriodEmpty() {
        Billing billing = billingService.issueMonthly(admin, a.getId(), MONTH,
                SeatType.GENERAL, 0, 12, null);

        // 27일(교습일수)과 31일(달력)이 달라 말일에서 12일을 빼면 틀린 날짜가 나온다.
        // 추측값을 넣으면 화면이 그것을 사실로 읽는다
        assertThat(billing.getServiceFrom()).isNull();
        assertThat(billing.getServiceTo()).isNull();
    }

    @Test
    @DisplayName("일할에 시작일을 주면 그 날부터 말일까지로 저장된다")
    void proratedWithStartDateStoresPeriod() {
        Billing billing = billingService.issueMonthly(admin, a.getId(), MONTH,
                SeatType.GENERAL, 0, 12, null, null, LocalDate.of(YEAR, 3, 15));

        assertThat(billing.getServiceFrom()).isEqualTo(LocalDate.of(YEAR, 3, 15));
        assertThat(billing.getServiceTo()).isEqualTo(LocalDate.of(YEAR, 3, 31));
        assertThat(billing.coversWholeMonth()).isFalse();
    }

    @Test
    @DisplayName("★★ wholeMonth 가 응답 JSON 에 실린다 — 레코드 파생 메서드는 기본으로 빠진다")
    void wholeMonthIsSerialized() {
        TuitionPrice price = pricingService.price(YEAR, GradeType.N_SU, SeatType.GENERAL,
                academy.getId());

        String whole = objectMapper.writeValueAsString(
                pricingService.breakdown(price, YEAR, 3, academy.getId(), 0, null));
        String partial = objectMapper.writeValueAsString(
                pricingService.breakdown(price, YEAR, 3, academy.getId(), 0, 12));

        // ★ 실제로 한 번 빠뜨렸다. 칸이 없으면 화면은 undefined 를 「일할」로 읽고,
        //   월 전체 청구에 나눗셈 식을 붙여 18원이 어긋난 값을 적는다
        assertThat(whole).contains("\"wholeMonth\":true");
        assertThat(partial).contains("\"wholeMonth\":false");
    }
}
