package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.service.ReceiptStatusService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
 * 수납현황·미납자 (F-4.8-1).
 *
 * <p>지키려는 것은 셋 — <b>미납액이 정확하다</b>, <b>취소된 수납은 안 센다</b>,
 * <b>엑셀 연락처가 마스킹된다</b>.
 */
@SpringBootTest
@Transactional
class ReceiptStatusTest {

    @Autowired ReceiptStatusService receiptStatusService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2094;

    Academy academy;
    Academy other;
    StudentEnrollment student;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("RS1", "수납테스트", LocalTime.of(9, 0));
        other = new Academy("RS2", "다른지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.persist(other);

        Student person = new Student("RS-0001", "김수납", "010-1234-5678");
        em.persist(person);
        student = new StudentEnrollment(person, academy, YEAR, "2094-0001", null, GradeType.N_SU);
        em.persist(student);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    private Billing billing(BillingType type, int amount, LocalDate dueDate) {
        Billing b = new Billing(student, type.name() + " 청구", type, amount, 0, dueDate);
        em.persist(b);
        em.flush();
        return b;
    }

    // ─────────────────────────────────────────── 미납액

    @Test
    @DisplayName("수납이 없으면 전액 미납이다")
    void unpaidWhenNoPayment() {
        billing(BillingType.TUITION, 750_000, LocalDate.of(YEAR, 3, 10));

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR, null, null, null, false);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.received()).isZero();
            assertThat(r.unpaid()).isEqualTo(750_000);
        });
    }

    @Test
    @DisplayName("분납하면 남은 만큼만 미납이다")
    void partialPayment() {
        Billing b = billing(BillingType.TUITION, 750_000, null);
        b.addPayment(300_000, PaymentMethod.CARD, Instant.now());
        em.flush();

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR, null, null, null, false);

        assertThat(rows.get(0).received()).isEqualTo(300_000);
        assertThat(rows.get(0).unpaid()).isEqualTo(450_000);
    }

    @Test
    @DisplayName("★ 취소된 수납은 세지 않는다 — 안 그러면 낸 적 없는 돈이 수납으로 잡힌다")
    void canceledPaymentIsExcluded() {
        Billing b = billing(BillingType.TUITION, 750_000, null);
        PaymentTransaction tx = b.addPayment(750_000, PaymentMethod.CARD, Instant.now());
        em.flush();

        tx.cancel(Instant.now());
        em.flush();

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR, null, null, null, false);

        assertThat(rows.get(0).received()).isZero();
        assertThat(rows.get(0).unpaid()).isEqualTo(750_000);
    }

    @Test
    @DisplayName("★ 과납이어도 미납액은 0에서 멈춘다 — 음수가 섞이면 합계가 줄어든다")
    void overpaymentDoesNotGoNegative() {
        Billing b = billing(BillingType.TUITION, 100_000, null);
        b.addPayment(150_000, PaymentMethod.CASH, Instant.now());
        em.flush();

        var summary = receiptStatusService.summarize(admin, academy.getId(), YEAR, null, null, null);

        assertThat(summary.unpaidAmount()).isZero();
        assertThat(summary.receivedAmount()).isEqualTo(150_000);
    }

    // ─────────────────────────────────────────── 필터

    @Test
    @DisplayName("미납만 걸러낸다 — 독촉 대상 추출이 이걸 쓴다")
    void unpaidOnlyFilter() {
        Billing paid = billing(BillingType.TUITION, 100_000, null);
        paid.addPayment(100_000, PaymentMethod.CARD, Instant.now());
        billing(BillingType.MEAL, 77_000, null);
        em.flush();

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR, null, null, null, true);

        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.billing().getBillingType())
                        .isEqualTo(BillingType.MEAL));
    }

    @Test
    @DisplayName("★ 납부기한이 없는 청구는 기간 필터에 걸리지 않는다 — 걸면 통째로 사라진다")
    void nullDueDateSurvivesDateFilter() {
        billing(BillingType.TUITION, 750_000, null);          // 기한 없음
        billing(BillingType.MEAL, 77_000, LocalDate.of(YEAR, 5, 10));
        em.flush();

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR,
                LocalDate.of(YEAR, 3, 1), LocalDate.of(YEAR, 3, 31), null, false);

        // 3월 기간인데 기한 없는 건은 남고, 5월 건만 빠진다
        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.billing().getDueDate()).isNull());
    }

    @Test
    @DisplayName("취소된 청구는 목록에 없다")
    void canceledBillingExcluded() {
        Billing b = billing(BillingType.TUITION, 750_000, null);
        b.cancel();
        em.flush();

        assertThat(receiptStatusService.find(admin, academy.getId(), YEAR, null, null, null, false))
                .isEmpty();
    }

    // ─────────────────────────────────────────── 합계

    @Test
    @DisplayName("★ 합계가 목록과 같은 조건으로 계산된다 — 다르면 어느 쪽을 믿을지 알 수 없다")
    void summaryMatchesList() {
        Billing tuition = billing(BillingType.TUITION, 750_000, null);
        tuition.addPayment(300_000, PaymentMethod.CARD, Instant.now());
        billing(BillingType.MEAL, 77_000, null);
        em.flush();

        var summary = receiptStatusService.summarize(admin, academy.getId(), YEAR, null, null, null);

        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.billedAmount()).isEqualTo(827_000);
        assertThat(summary.receivedAmount()).isEqualTo(300_000);
        assertThat(summary.unpaidAmount()).isEqualTo(527_000);
        assertThat(summary.unpaidCount()).isEqualTo(2);
        assertThat(summary.unpaidByType())
                .containsEntry(BillingType.TUITION, 450_000)
                .containsEntry(BillingType.MEAL, 77_000);
    }

    // ─────────────────────────────────────────── 지점 · 엑셀

    @Test
    @DisplayName("★ 다른 지점 건은 안 보인다")
    void otherAcademyExcluded() {
        Student p = new Student("RS-0002", "남의지점", "010-9999-8888");
        em.persist(p);
        StudentEnrollment e = new StudentEnrollment(p, other, YEAR, "2094-0002", null,
                GradeType.N_SU);
        em.persist(e);
        em.persist(new Billing(e, "남의 청구", BillingType.TUITION, 750_000, 0, null));
        billing(BillingType.TUITION, 100_000, null);
        em.flush();

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR, null, null, null, false);

        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.billing().getBilledAmount()).isEqualTo(100_000));
    }

    @Test
    @DisplayName("★ 전 지점 권한자도 지점을 골라야 한다 — 구분 없이 독촉이 나간다")
    void allAcademyAdminMustPickOne() {
        assertThatThrownBy(() -> receiptStatusService.find(
                admin, null, YEAR, null, null, null, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 엑셀 연락처가 마스킹된다 — 그대로 다시 올리면 전 학생 번호가 덮어써진다")
    void exportMasksPhone() {
        billing(BillingType.TUITION, 750_000, null);
        em.flush();

        byte[] bytes = receiptStatusService.export(admin, academy.getId(), YEAR,
                null, null, null, false);

        assertThat(bytes).isNotEmpty();
        // 원본 번호가 파일 어디에도 남으면 안 된다
        String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("010-1234-5678");
    }
}
