package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.BillingItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillingItemRepository extends JpaRepository<BillingItem, Long> {

    /**
     * 청구 여럿의 항목을 <b>한 번에</b> 읽는다.
     *
     * <p>★ {@code Billing.items} 는 lazy 컬렉션이라 목록 응답에서 행마다 꺼내면
     * <b>쿼리가 청구 수만큼 나간다</b>(지점 전체 수납현황은 수백 건이다).
     * 그렇다고 목록 쿼리에 {@code JOIN FETCH b.items} 를 더할 수도 없다 —
     * {@code b.transactions} 를 이미 fetch 하는 쿼리가 있어 <b>컬렉션 두 개를 함께 fetch 하면
     * {@code MultipleBagFetchException}</b> 이 난다. 그래서 ID 를 모아 따로 읽는다
     * (수납현황이 거래를 읽는 방식과 같다).
     */
    @Query("""
            SELECT i FROM BillingItem i
            WHERE i.billing.id IN :billingIds
              AND i.deleted = false
            ORDER BY i.billing.id ASC, i.sortOrder ASC, i.id ASC
            """)
    List<BillingItem> findByBillingIds(@Param("billingIds") Collection<Long> billingIds);
}
