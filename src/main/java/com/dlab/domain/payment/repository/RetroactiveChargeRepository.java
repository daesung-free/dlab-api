package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.RetroactiveCharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RetroactiveChargeRepository extends JpaRepository<RetroactiveCharge, Long> {

    /**
     * 이미 소급된 청구인가.
     *
     * <p>부분 유니크 인덱스가 최후 방어선이지만 먼저 걸러야 <b>"이미 소급됨"이라는
     * 제대로 된 안내</b>가 나간다. 두 번 소급되면 학생이 두 번 낸다.
     */
    @Query("""
            SELECT r.sourceBilling.id FROM RetroactiveCharge r
            WHERE r.sourceBilling.id IN :billingIds AND r.deleted = false
            """)
    List<Long> findAlreadyChargedSourceIds(@Param("billingIds") List<Long> billingIds);

    /** 소급 청구 한 건의 달별 내역. 상세 내역 출력이 쓴다. */
    @Query("""
            SELECT r FROM RetroactiveCharge r
            WHERE r.chargeBilling.id = :chargeBillingId AND r.deleted = false
            ORDER BY r.serviceYear, r.serviceMonth
            """)
    List<RetroactiveCharge> findByChargeBilling(@Param("chargeBillingId") Long chargeBillingId);
}
