package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.CashReceipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CashReceiptRepository extends JpaRepository<CashReceipt, Long> {

    @Query("""
            SELECT r FROM CashReceipt r
            WHERE r.billing.id = :billingId AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<CashReceipt> findByBillingId(Long billingId);

    /** 이 수납에 유효한 영수증이 이미 있는지. 이중 발급을 막는다 */
    @Query("""
            SELECT r FROM CashReceipt r
            WHERE r.transaction.id = :transactionId
              AND r.status = com.dlab.domain.payment.entity.CashReceiptStatus.ISSUED
              AND r.deleted = false
            """)
    Optional<CashReceipt> findIssuedByTransactionId(Long transactionId);
}
