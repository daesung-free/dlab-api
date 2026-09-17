package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.PaymentRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    /** Webhook 이 우리 주문번호로 돌아온다. */
    Optional<PaymentRequest> findByOrderNo(String orderNo);

    /**
     * 같은 승인이 이미 처리됐는지.
     *
     * <p>Webhook 은 우리가 {@code result=0000} 을 돌려줄 때까지 최대 10번 재전송된다 —
     * 이 조회가 없으면 같은 결제로 수납이 여러 번 잡힌다.
     */
    Optional<PaymentRequest> findByTno(String tno);

    @Query("""
            SELECT r FROM PaymentRequest r
            WHERE r.billing.id = :billingId AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<PaymentRequest> findByBillingId(Long billingId);
}
