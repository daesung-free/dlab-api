package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.PaymentTransaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /**
     * 여러 청구의 <b>살아 있는</b> 수납 거래를 한 번에.
     *
     * <p>목록 화면이 청구마다 결제수단을 조회하면 쿼리가 행 수만큼 나간다
     * (급식 결제·취소 내역은 재원생 전체가 대상이다). ID를 모아 한 번에 읽고
     * {@code Map}으로 붙이는 용도다.
     *
     * <p>취소된 거래는 뺀다 — 취소분까지 세면 화면의 결제수단이
     * "실제로 지금 결제된 수단"과 어긋난다. 이력 자체는 지우지 않으므로
     * 정산 추적은 그대로 남는다.
     */
    @Query("""
            SELECT t FROM PaymentTransaction t
            WHERE t.billing.id IN :billingIds
              AND t.canceledAt IS NULL
              AND t.deleted = false
            ORDER BY t.paidAt ASC
            """)
    List<PaymentTransaction> findActiveByBillingIds(@Param("billingIds") List<Long> billingIds);
}
