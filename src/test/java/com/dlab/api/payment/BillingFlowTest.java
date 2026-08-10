package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.kiosk.service.KioskReceiptQueryService;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingStatus;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.entity.PaymentTransaction;
import com.dlab.domain.payment.service.BillingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
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
 * 청구·수납 (F-4.8-1) + 키오스크 수납 조회 (3.29).
 *
 * <p>결제(PG)는 없다 — 수납은 수기 기록이다.
 */
@SpringBootTest
@Transactional
class BillingFlowTest {

    @Autowired BillingService billingService;
    @Autowired KioskReceiptQueryService receiptQueryService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-1", "김민지", "010-0000-0000");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", "CARD001", GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private Billing bill(int supplied, int discount) {
        Billing b = billingService.create(admin, minji.getId(), "2026학년도 1기 교습비",
                BillingType.TUITION, supplied, discount, LocalDate.now().plusDays(7));
        em.flush();
        return b;
    }

    @Test
    @DisplayName("★ 청구액을 저장한다 — 할인 정책이 바뀌어도 과거 청구액이 소급 변경되면 안 된다")
    void billedAmountIsStored() {
        Billing b = bill(1_000_000, 200_000);

        assertThat(b.getBilledAmount()).isEqualTo(800_000);
        assertThat(b.unpaidAmount()).isEqualTo(800_000);
        assertThat(b.getStatus()).isEqualTo(BillingStatus.PENDING);
    }

    @Test
    @DisplayName("★ 분납이 된다 — 청구 1건에 거래가 여러 건 붙는다")
    void supportsPartialPayments() {
        Billing b = bill(1_000_000, 0);

        billingService.pay(admin, b.getId(), 400_000, PaymentMethod.CASH);
        em.flush();
        assertThat(b.receivedAmount()).isEqualTo(400_000);
        assertThat(b.unpaidAmount()).isEqualTo(600_000);
        assertThat(b.getStatus()).isEqualTo(BillingStatus.PENDING);

        billingService.pay(admin, b.getId(), 600_000, PaymentMethod.TRANSFER);
        em.flush();
        assertThat(b.unpaidAmount()).isZero();
        assertThat(b.getStatus()).isEqualTo(BillingStatus.PAID);
    }

    @Test
    @DisplayName("초과 입금을 막지 않는다 — 막으면 데스크가 기록 자체를 안 남긴다")
    void overpaymentIsAllowed() {
        Billing b = bill(100_000, 0);
        billingService.pay(admin, b.getId(), 150_000, PaymentMethod.CASH);
        em.flush();

        assertThat(b.receivedAmount()).isEqualTo(150_000);
        assertThat(b.unpaidAmount()).isZero();   // 음수로 내려가지 않는다
    }

    @Test
    @DisplayName("★ 수납 취소는 거래를 지우지 않고 다시 미납으로 내린다")
    void cancelPaymentRestoresUnpaid() {
        Billing b = bill(500_000, 0);
        PaymentTransaction tx = billingService.pay(admin, b.getId(), 500_000, PaymentMethod.CARD);
        em.flush();
        assertThat(b.getStatus()).isEqualTo(BillingStatus.PAID);

        billingService.cancelPayment(admin, tx.getId());
        em.flush();

        assertThat(b.getStatus()).isEqualTo(BillingStatus.PENDING);
        assertThat(b.unpaidAmount()).isEqualTo(500_000);
        assertThat(em.find(PaymentTransaction.class, tx.getId())).isNotNull();   // 행은 남는다
    }

    @Test
    @DisplayName("할인이 정가보다 크면 거부")
    void discountCannotExceedSupplied() {
        assertThatThrownBy(() -> bill(100_000, 200_000))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 다른 지점 학생에게는 청구할 수 없다")
    void otherAcademyStudentIsRejected() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> billingService.create(ilsanAdmin, minji.getId(), "교습비",
                BillingType.TUITION, 100_000, 0, null))
                .isInstanceOf(BusinessException.class);
    }

    // ── 키오스크 3.29 ─────────────────────────────────────────

    @Test
    @DisplayName("★★ 키오스크 수납 조회 — 정가·수납액·미납액이 맞아떨어진다")
    void kioskReceiptReflectsPayments() {
        Billing b = bill(1_000_000, 200_000);
        billingService.pay(admin, b.getId(), 300_000, PaymentMethod.CASH);
        em.flush();
        em.clear();

        var rows = receiptQueryService.receipts(bundang.getId(), "CARD001");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rcvNm()).isEqualTo("2026학년도 1기 교습비");
        assertThat(rows.get(0).suppAmt()).isEqualTo("1000000");   // 정가
        assertThat(rows.get(0).recAmt()).isEqualTo("300000");     // 수납
        assertThat(rows.get(0).miAmt()).isEqualTo("500000");      // 청구액 800000 − 300000
    }

    @Test
    @DisplayName("★ 완납 건도 내린다 — 미납만 주면 '낸 것'이 화면에서 사라진다")
    void paidBillingIsStillListed() {
        Billing b = bill(100_000, 0);
        billingService.pay(admin, b.getId(), 100_000, PaymentMethod.CARD);
        em.flush();
        em.clear();

        assertThat(receiptQueryService.receipts(bundang.getId(), "CARD001")).hasSize(1);
    }

    @Test
    @DisplayName("취소된 청구는 안 내린다")
    void cancelledBillingIsHidden() {
        Billing b = bill(100_000, 0);
        billingService.cancel(admin, b.getId());
        em.flush();
        em.clear();

        assertThat(receiptQueryService.receipts(bundang.getId(), "CARD001")).isEmpty();
    }

    @Test
    @DisplayName("★ 모르는 카드는 빈 목록이다 — 예외를 던지면 학생 상세 화면 전체가 실패한다")
    void unknownCardReturnsEmpty() {
        assertThat(receiptQueryService.receipts(bundang.getId(), "NOPE")).isEmpty();
        assertThat(receiptQueryService.receipts(bundang.getId(), null)).isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 카드는 빈 목록이다")
    void otherAcademyCardReturnsEmpty() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();
        bill(100_000, 0);
        em.flush();
        em.clear();

        assertThat(receiptQueryService.receipts(ilsan.getId(), "CARD001")).isEmpty();
    }
}
