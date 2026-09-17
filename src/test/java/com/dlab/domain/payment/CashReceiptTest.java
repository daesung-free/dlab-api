package com.dlab.domain.payment;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.service.CashReceiptService;
import com.dlab.domain.user.entity.*;
import com.dlab.integration.pg.KcpBuyLinkClient;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 현금영수증.
 *
 * <p>지키려는 것 — <b>카드 수납에는 내지 말 것</b>, <b>같은 수납에 두 번 내지 말 것</b>,
 * <b>취소해도 기록이 남을 것</b>.
 */
@SpringBootTest
@Transactional
class CashReceiptTest {

    @Autowired CashReceiptService service;
    @Autowired EntityManager em;

    @MockitoBean KcpBuyLinkClient client;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2096;

    Billing billing;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-C1", "김현금", "010-1111-2222");
        em.persist(student);
        StudentEnrollment enrollment =
                new StudentEnrollment(student, bundang, YEAR, "2096-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        billing = new Billing(enrollment, "2096년 9월 교습비",
                BillingType.TUITION, 750_000, 0, LocalDate.of(2096, 9, 10));
        em.persist(billing);

        em.persist(new PgSite(null, PgPurpose.TUITION, PgChannel.CASH_RECEIPT,
                "AO8M2", "대성학력개발 현금영수증", null));
        em.flush();

        admin = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN),
                true, false);

        when(client.issueCashReceipt(any()))
                .thenReturn(new KcpBuyLinkClient.CashReceiptIssued("CASH-1", "RCPT-1"));
        // ★ 취소 승인번호는 발급 승인번호와 다른 값으로 온다
        when(client.cancelCashReceipt(any()))
                .thenReturn(new KcpBuyLinkClient.CashReceiptCanceled("CASH-1", "RCPT-CANCEL-1"));
    }

    private PaymentTransaction pay(PaymentMethod method) {
        PaymentTransaction tx = billing.addPayment(750_000, method, Instant.now());
        em.flush();
        return tx;
    }

    @Test
    @DisplayName("★ 카드 수납에는 발급하지 않는다 — 카드사가 이미 소득공제를 처리한다")
    void cardIsNotEligible() {
        PaymentTransaction card = pay(PaymentMethod.CARD);

        assertThatThrownBy(() ->
                service.issue(admin, card.getId(), ReceiptPurpose.PERSONAL, "01011112222"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카드사가 소득공제");
    }

    @Test
    @DisplayName("계좌이체 수납에 발급한다 — 공급가액과 부가세를 나눠 저장한다")
    void issuesForTransfer() {
        PaymentTransaction transfer = pay(PaymentMethod.TRANSFER);

        CashReceipt receipt = service.issue(admin, transfer.getId(),
                ReceiptPurpose.PERSONAL, "01011112222");
        em.flush();

        assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
        assertThat(receipt.getCashNo()).isEqualTo("CASH-1");
        // 750,000 = 681,818 + 68,182 (원 단위 버림)
        assertThat(receipt.getSupplyAmount()).isEqualTo(681_818);
        assertThat(receipt.getTaxAmount()).isEqualTo(68_182);
        assertThat(receipt.getSupplyAmount() + receipt.getTaxAmount()).isEqualTo(750_000);
    }

    @Test
    @DisplayName("★ 같은 수납에 두 번 발급하지 않는다 — 이중 신고가 된다")
    void cannotIssueTwice() {
        PaymentTransaction transfer = pay(PaymentMethod.TRANSFER);
        service.issue(admin, transfer.getId(), ReceiptPurpose.PERSONAL, "01011112222");
        em.flush();

        assertThatThrownBy(() ->
                service.issue(admin, transfer.getId(), ReceiptPurpose.PERSONAL, "01011112222"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 발급된");
    }

    @Test
    @DisplayName("★ 취소 승인번호는 발급 승인번호와 따로 남는다 — 국세청에는 별개 건으로 등록된다")
    void keepsCancelReceiptNo() {
        PaymentTransaction transfer = pay(PaymentMethod.TRANSFER);
        CashReceipt receipt = service.issue(admin, transfer.getId(),
                ReceiptPurpose.PERSONAL, "01011112222");
        em.flush();

        service.cancel(admin, receipt.getId());
        em.flush();

        assertThat(receipt.getReceiptNo()).isEqualTo("RCPT-1");
        assertThat(receipt.getCancelReceiptNo()).isEqualTo("RCPT-CANCEL-1");
    }

    @Test
    @DisplayName("★★ KCP 가 취소를 거절하면 발급 상태 그대로다 — 우리만 취소로 바꾸면 국세청과 어긋난다")
    void keepsIssuedWhenKcpRejects() {
        PaymentTransaction transfer = pay(PaymentMethod.TRANSFER);
        CashReceipt receipt = service.issue(admin, transfer.getId(),
                ReceiptPurpose.PERSONAL, "01011112222");
        em.flush();

        when(client.cancelCashReceipt(any()))
                .thenThrow(new BusinessException(ErrorCode.PG_REQUEST_FAILED, "취소 실패"));

        assertThatThrownBy(() -> service.cancel(admin, receipt.getId()))
                .isInstanceOf(BusinessException.class);

        assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
        assertThat(receipt.getCanceledAt()).isNull();
    }

    @Test
    @DisplayName("★ 취소해도 기록이 남는다 — 지우면 「발급한 적 없음」과 구분되지 않는다")
    void cancelKeepsRecord() {
        PaymentTransaction transfer = pay(PaymentMethod.TRANSFER);
        CashReceipt receipt = service.issue(admin, transfer.getId(),
                ReceiptPurpose.PERSONAL, "01011112222");
        em.flush();

        service.cancel(admin, receipt.getId());
        em.flush();

        assertThat(receipt.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
        assertThat(receipt.getCanceledAt()).isNotNull();
        assertThat(service.byBilling(billing.getId())).hasSize(1);

        // 취소 후에는 다시 발급할 수 있다 — 잘못 낸 것을 고치는 경로다
        service.issue(admin, transfer.getId(), ReceiptPurpose.BUSINESS, "1088166057");
        em.flush();
        assertThat(service.byBilling(billing.getId())).hasSize(2);
    }
}
