package com.dlab.domain.appconfig.repository;

import com.dlab.domain.appconfig.entity.Terms;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TermsRepository extends JpaRepository<Terms, Long> {

    /**
     * 같은 지점 범위에 같은 code·version이 있는가.
     *
     * <p>공통본과 지점본은 <b>서로 막지 않는다</b> — 지점 문구가 다르면 같은 버전으로
     * 각자 존재하는 것이 정상이다.
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM Terms t
            WHERE t.code = :code AND t.version = :version AND t.deleted = false
              AND ((:academyId IS NULL AND t.academy IS NULL)
                   OR (t.academy IS NOT NULL AND t.academy.id = :academyId))
            """)
    boolean existsSameVersion(@Param("code") String code, @Param("version") String version,
                              @Param("academyId") Long academyId);

    /**
     * 코드별 <b>현재 시행 중인 최신 버전</b> 하나씩 — 지점 범위까지 반영한다.
     *
     * <p>미래 시행일(예약 등록)은 제외한다 — 아직 시행 전인 약관에 동의를 받으면
     * "무엇에 동의했나"가 어긋난다.
     *
     * <p><b>★ 지점 것이 공통을 이긴다.</b> 같은 {@code code}에 공통본과 지점본이 둘 다
     * 있으면 지점본만 내린다 — 둘 다 내리면 학생에게 같은 약관이 두 번 뜨고,
     * 어느 쪽에 동의해야 하는지 앱이 알 수 없다.
     *
     * <p>{@code academyId}가 {@code null}이면(지점을 특정할 수 없는 계정) 공통본만 본다.
     * 지점 약관을 섞어 보여주면 그 지점 소속이 아닌 사람에게 남의 지점 문구가 나간다.
     */
    @Query("""
            SELECT t FROM Terms t
            WHERE t.deleted = false
              AND t.effectiveAt <= :now
              AND (t.academy IS NULL OR t.academy.id = :academyId)
              AND (t.academy IS NOT NULL OR NOT EXISTS (
                    SELECT 1 FROM Terms o
                    WHERE o.code = t.code AND o.deleted = false
                      AND o.effectiveAt <= :now AND o.academy.id = :academyId))
              AND t.effectiveAt = (
                    SELECT MAX(t2.effectiveAt) FROM Terms t2
                    WHERE t2.code = t.code AND t2.deleted = false
                      AND t2.effectiveAt <= :now
                      AND ((t2.academy IS NULL AND t.academy IS NULL)
                           OR (t2.academy IS NOT NULL AND t.academy IS NOT NULL
                               AND t2.academy.id = t.academy.id)))
            ORDER BY t.required DESC, t.code
            """)
    List<Terms> findCurrent(@Param("now") Instant now, @Param("academyId") Long academyId);
}
