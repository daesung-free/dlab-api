package com.dlab.domain.payment;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.repository.PaymentRequestRepository;
import com.dlab.domain.payment.repository.PgSiteRepository;
import com.dlab.domain.payment.service.PaymentRequestService;
import com.dlab.domain.user.entity.*;
import com.dlab.integration.pg.KcpBuyLinkClient;
import jakarta.persistence.EntityManager;
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
 * 결제 링크 생성과 Webhook 확정.
 *
 * <p>지키려는 것 — <b>링크 생성만으로 수납이 잡히지 않을 것</b>,
 * <b>재전송에도 수납이 한 번만 잡힐 것</b>, <b>금액이 다르면 멈출 것</b>.
 */
@SpringBootTest
@Transactional
class PaymentWebhookTest {

    @Autowired PaymentRequestService service;
    @Autowired PaymentRequestRepository requestRepository;
    @Autowired PgSiteRepository pgSiteRepository;
    @Autowired EntityManager em;

    @MockitoBean KcpBuyLinkClient client;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2097;

    Academy bundang;
    Billing billing;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-P1", "김결제", "010-1234-5678");
        em.persist(student);
        StudentEnrollment enrollment =
                new StudentEnrollment(student, bundang, YEAR, "2097-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        billing = new Billing(enrollment, "2097년 9월 교습비",
                BillingType.TUITION, 750_000, 0, LocalDate.of(2097, 9, 10));
        em.persist(billing);

        em.persist(new PgSite(null, PgPurpose.TUITION, PgChannel.BUYLINK,
                "AO8M2", "대성학력개발 바이링크", null));
        em.flush();

        admin = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN),
                true, false);

        when(client.createPayUrl(any())).thenReturn(
                new KcpBuyLinkClient.Created("https://pay.kcp.co.kr/abc", "urlreg-1"));
    }

    private PaymentRequest createLink() {
        return service.createBuyLink(admin, billing.getId(), PayMethod.CARD, true);
    }

    @Test
    @DisplayName("★ 링크를 만들었다고 수납이 잡히지 않는다 — 받은 적 없는 돈이 완납으로 기록된다")
    void creatingLinkDoesNotSettle() {
        PaymentRequest request = createLink();
        em.flush();

        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.CREATED);
        assertThat(billing.receivedAmount()).isZero();
        assertThat(billing.getStatus()).isEqualTo(BillingStatus.PENDING);
    }

    @Test
    @DisplayName("★★ Webhook 이 재전송돼도 수납은 한 번만 잡힌다 — KCP 는 최대 10번 보낸다")
    void webhookIsIdempotent() {
        PaymentRequest request = createLink();
        em.flush();

        boolean first = service.confirm(request.getOrderNo(), "TNO-1", 750_000, "현대카드");
        boolean second = service.confirm(request.getOrderNo(), "TNO-1", 750_000, "현대카드");
        em.flush();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(billing.receivedAmount()).isEqualTo(750_000);
        assertThat(billing.getStatus()).isEqualTo(BillingStatus.PAID);
    }

    @Test
    @DisplayName("★ 승인 금액이 요청과 다르면 수납을 잡지 않는다")
    void amountMismatchIsRejected() {
        PaymentRequest request = createLink();
        em.flush();

        assertThatThrownBy(() -> service.confirm(request.getOrderNo(), "TNO-2", 1_000, "현대카드"))
                .isInstanceOf(BusinessException.class);

        assertThat(billing.receivedAmount()).isZero();
    }

    @Test
    @DisplayName("완납된 청구에는 링크를 다시 보내지 않는다 — 학부모가 두 번 낸다")
    void cannotCreateLinkForPaidBilling() {
        PaymentRequest request = createLink();
        service.confirm(request.getOrderNo(), "TNO-3", 750_000, "현대카드");
        em.flush();

        assertThatThrownBy(this::createLink)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 완납");
    }

    @Test
    @DisplayName("★ 가상계좌 발급은 결제가 아니다 — 계좌번호만 나왔고 입금은 나중이다")
    void vbankIssueIsNotPayment() {
        em.persist(new PgSite(null, PgPurpose.TUITION, PgChannel.VBANK,
                "AO8M2", "대성학력개발 가상계좌", null));
        em.flush();
        org.mockito.Mockito.when(client.issueVbank(any())).thenReturn(
                new KcpBuyLinkClient.VbankIssued("TNO-V1", "T2609260001713",
                        "신한은행", "BK26", "대성학력개발"));

        PaymentRequest request = service.issueVbank(admin, billing.getId(), "BK26", 7);
        em.flush();

        assertThat(request.getVbankAccount()).isEqualTo("T2609260001713");
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.CREATED);
        assertThat(billing.receivedAmount()).isZero();

        // 입금 통보가 와야 수납이 잡힌다
        service.confirmVbankDeposit(request.getOrderNo(), "TNO-V1", 750_000, "김할머니");
        em.flush();

        assertThat(billing.receivedAmount()).isEqualTo(750_000);
        // ⚠️ 입금자가 학생·학부모와 다를 수 있다 — 기록만 하고 대조하지 않는다
        assertThat(request.getVbankRemitter()).isEqualTo("김할머니");
    }

    @Test
    @DisplayName("★ 급식비는 업체 명의 사이트코드가 없으면 거절한다 — 학원 코드로 받으면 업체에게 안 간다")
    void mealRequiresVendorSite() {
        Billing meal = new Billing(billing.getEnrollment(), "9월 급식비",
                BillingType.MEAL, 55_000, 0, LocalDate.of(2097, 9, 10));
        em.persist(meal);
        em.flush();

        assertThatThrownBy(() ->
                service.createBuyLink(admin, meal.getId(), PayMethod.CARD, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("사이트코드가 등록되지 않았습니다");
    }
}
