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

    /**
     * 여러 계정 × 여러 약관의 <b>최신</b> 동의 여부. 동의율 집계용.
     *
     * <p>동의는 이력이라(철회도 행으로 쌓인다) 계정·약관마다 마지막 행만 본다.
     *
     * @return {@code [계정 id, 약관 id, 동의 여부]}
     */
    @Query("""
            SELECT a.account.id, a.terms.id, a.agreed FROM TermAgreement a
            WHERE a.account.id IN :accountIds
              AND a.terms.id IN :termsIds
              AND a.id = (
                    SELECT MAX(a2.id) FROM TermAgreement a2
                    WHERE a2.account = a.account AND a2.terms = a.terms)
            """)
    List<Object[]> findLatestOf(@Param("accountIds") java.util.Collection<Long> accountIds,
                                @Param("termsIds") java.util.Collection<Long> termsIds);
}
