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

    /**
     * ★ 이 약관에 대한 응답이 한 건이라도 있나 — <b>치울 수 있는지 판정</b>에 쓴다.
     *
     * <p>⚠️ <b>동의/미동의를 가리지 않는다.</b> 미동의도 「그때 거부했다」는 기록이라,
     * 지우면 <b>「물어봤는데 거부했다」와 「물어본 적 없다」가 구분되지 않는다.</b>
     */
    boolean existsByTermsId(Long termsId);

    /** 전체 이력 — 감사·분쟁 대응용. 최신순. */
    List<TermAgreement> findByAccountIdOrderByAgreedAtDesc(Long accountId);

    /**
     * 여러 계정 × 여러 약관의 <b>최신</b> 동의 여부. 동의율 집계용.
     *
     * <p>동의는 이력이라(철회도 행으로 쌓인다) 계정·약관마다 마지막 행만 본다.
     *
     * <p>★ {@code agreedAt} 을 함께 준다 — <b>유효기간이 있는 약관</b>(마케팅: 동의일부터
     * 2년)은 <b>동의했는지만으로는 유효한지 알 수 없다.</b> 이것 없이 세면 <b>2년 지난
     * 동의자가 「동의자」로 잡힌다.</b>
     *
     * @return {@code [계정 id, 약관 id, 동의 여부, 동의 시각]}
     */
    @Query("""
            SELECT a.account.id, a.terms.id, a.agreed, a.agreedAt FROM TermAgreement a
            WHERE a.account.id IN :accountIds
              AND a.terms.id IN :termsIds
              AND a.id = (
                    SELECT MAX(a2.id) FROM TermAgreement a2
                    WHERE a2.account = a.account AND a2.terms = a.terms)
            """)
    List<Object[]> findLatestOf(@Param("accountIds") java.util.Collection<Long> accountIds,
                                @Param("termsIds") java.util.Collection<Long> termsIds);
}
