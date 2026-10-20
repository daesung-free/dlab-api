package com.dlab.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.PaymentRequest;
import com.dlab.domain.payment.entity.PaymentRequestStatus;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.PgSite;
import com.dlab.domain.payment.service.PaymentRequestService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
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

/**
 * 가상계좌 <b>말소</b> (디랩 10/12 회신 ①).
 *
 * <h2>★★ 안 닫으면 취소된 청구로 돈이 들어온다</h2>
 * 가상계좌는 발급해 두면 <b>기한까지 계속 입금을 받는다.</b> 청구를 취소했는데 계좌를
 * 그대로 두면 학부모가 그 번호로 입금하고, 그 돈은 <b>어디에 붙일지 모르는 입금</b>이 된다.
 *
 * <h2>★ 입금된 건은 말소가 아니라 환불이다</h2>
 * 입금이 확정된 건을 말소로 처리하면 <b>돈은 우리에게 있는데 거래가 사라진다.</b>
 */
@SpringBootTest
@Transactional
class VbankCloseTest {

    @Autowired PaymentRequestService service;
    @Autowired EntityManager em;

    @MockitoBean KcpBuyLinkClient client;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2092;

    Billing billing;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        Academy academy = new Academy("VB1", "말소테스트", LocalTime.of(9, 0));
        em.persist(academy);
        Student student = new Student("VB-0001", "가상계좌학생", "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(student, academy, YEAR,
                "2092-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        billing = new Billing(enrollment, "3월 교습비", BillingType.TUITION,
                750_000, 0, LocalDate.of(YEAR, 3, 10));
        em.persist(billing);
        em.persist(new PgSite(null, PgPurpose.TUITION, PgChannel.VBANK,
                "AO8M2", "가상계좌", null));
        em.flush();

        admin = new AuthPrincipal(1L, "admin", academy.getId(), Set.of(Role.SUPER_ADMIN),
                true, false);

        when(client.issueVbank(any())).thenReturn(new KcpBuyLinkClient.VbankIssued(
                "TNO-VB-1", "123456789", "국민은행", "004", "대성학원"));
        when(client.closeVbank(any())).thenReturn(new KcpBuyLinkClient.VbankClosed("TNO-VB-1"));
    }

    private PaymentRequest issued() {
        PaymentRequest r = service.issueVbank(admin, billing.getId(), "004", 7);
        em.flush();
        return r;
    }

    @Test
    @DisplayName("★ 입금 전 가상계좌를 말소하면 상태가 취소로 바뀐다 — 그 계좌로 더 못 들어온다")
    void closeBeforeDeposit() {
        PaymentRequest r = issued();

        service.closeVbank(admin, r.getId());

        assertThat(r.getStatus()).isEqualTo(PaymentRequestStatus.CANCELED);
        verify(client).closeVbank(any());
    }

    @Test
    @DisplayName("★★ 이미 입금된 가상계좌는 말소할 수 없다 — 돈은 있는데 거래가 사라진다")
    void paidVbankCannotBeClosed() {
        PaymentRequest r = issued();
        service.confirmVbankDeposit(r.getOrderNo(), "TNO-VB-1", 750_000, "학부모");
        em.flush();

        assertThatThrownBy(() -> service.closeVbank(admin, r.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("환불");

        // ★ PG 에 아무것도 보내지 않았다 — 보내면 입금된 거래가 취소된다
        verify(client, never()).closeVbank(any());
    }

    @Test
    @DisplayName("★ 두 번 눌러도 안전하다 — PG 에 두 번 보내지 않는다")
    void closeIsIdempotent() {
        PaymentRequest r = issued();

        service.closeVbank(admin, r.getId());
        service.closeVbank(admin, r.getId());

        assertThat(r.getStatus()).isEqualTo(PaymentRequestStatus.CANCELED);
        verify(client).closeVbank(any());   // 한 번만
    }

    @Test
    @DisplayName("★★ PG 말소가 실패하면 우리 상태도 바꾸지 않는다 — 계좌는 살아 있는데 닫힌 것으로 보인다")
    void failureLeavesStatusUnchanged() {
        PaymentRequest r = issued();
        when(client.closeVbank(any())).thenThrow(
                new BusinessException(com.dlab.common.exception.ErrorCode.PG_REQUEST_FAILED,
                        "말소 실패"));

        assertThatThrownBy(() -> service.closeVbank(admin, r.getId()))
                .isInstanceOf(BusinessException.class);

        assertThat(r.getStatus()).isEqualTo(PaymentRequestStatus.CREATED);
    }
}
