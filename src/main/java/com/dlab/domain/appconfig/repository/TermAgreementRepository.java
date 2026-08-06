package com.dlab.domain.appconfig.repository;

import com.dlab.domain.appconfig.entity.TermAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TermAgreementRepository extends JpaRepository<TermAgreement, Long> {

    /**
     * 계정의 <b>약관별 최신 동의 상태</b>.
     *
     * <p>이력이 쌓이는 테이블이라 그냥 조회하면 철회 전 기록까지 함께 나온다 —
     * 약관마다 가장 최근 행 하나씩만 뽑는다.
     */
    @Query("""
            SELECT a FROM TermAgreement a
            JOIN FETCH a.terms t
            WHERE a.account.id = :accountId
              AND a.id = (
                    SELECT MAX(a2.id) FROM TermAgreement a2
                    WHERE a2.account.id = :accountId AND a2.terms = a.terms)
            """)
    List<TermAgreement> findLatestByAccount(@Param("accountId") Long accountId);

    /** 전체 이력 — 감사·분쟁 대응용. 최신순. */
    List<TermAgreement> findByAccountIdOrderByAgreedAtDesc(Long accountId);
}
