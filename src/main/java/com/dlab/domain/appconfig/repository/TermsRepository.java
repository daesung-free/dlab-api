package com.dlab.domain.appconfig.repository;

import com.dlab.domain.appconfig.entity.Terms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TermsRepository extends JpaRepository<Terms, Long> {

    boolean existsByCodeAndVersionAndDeletedFalse(String code, String version);

    /**
     * 코드별 <b>현재 시행 중인 최신 버전</b> 하나씩.
     *
     * <p>미래 시행일(예약 등록)은 제외한다 — 아직 시행 전인 약관에 동의를 받으면
     * "무엇에 동의했나"가 어긋난다.
     */
    @Query("""
            SELECT t FROM Terms t
            WHERE t.deleted = false
              AND t.effectiveAt <= :now
              AND t.effectiveAt = (
                    SELECT MAX(t2.effectiveAt) FROM Terms t2
                    WHERE t2.code = t.code AND t2.deleted = false AND t2.effectiveAt <= :now)
            ORDER BY t.required DESC, t.code
            """)
    List<Terms> findCurrent(@Param("now") Instant now);
}
