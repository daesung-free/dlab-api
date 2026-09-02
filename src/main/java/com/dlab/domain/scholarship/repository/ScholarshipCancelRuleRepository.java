package com.dlab.domain.scholarship.repository;

import com.dlab.domain.scholarship.entity.ScholarshipCancelRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScholarshipCancelRuleRepository extends JpaRepository<ScholarshipCancelRule, Long> {

    /**
     * 그 지점에 적용되는 <b>켜진</b> 규칙.
     *
     * <p>지점 행이 있으면 그것만, 없으면 공통본을 쓴다(교습비 가격·성적 양식과 같은 규약).
     * 둘을 합치면 같은 요건이 두 번 판정돼 검토 대상이 중복으로 쌓인다.
     *
     * <p><b>{@code active = false}는 아예 안 나온다</b> — 켜기 전엔 판정하지 않는다.
     */
    @Query("""
            SELECT r FROM ScholarshipCancelRule r
            WHERE r.year = :year AND r.active = true AND r.deleted = false
              AND (
                    r.academy.id = :academyId
                 OR (r.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM ScholarshipCancelRule o
                        WHERE o.year = :year AND o.ruleType = r.ruleType
                          AND o.academy.id = :academyId AND o.deleted = false))
              )
            """)
    List<ScholarshipCancelRule> findActive(@Param("year") short year,
                                           @Param("academyId") Long academyId);

    /** 관리자 화면 — 꺼진 것까지 전부. */
    @Query("""
            SELECT r FROM ScholarshipCancelRule r
            WHERE r.year = :year AND r.deleted = false
              AND (:academyId IS NULL AND r.academy IS NULL OR r.academy.id = :academyId)
            ORDER BY r.ruleType
            """)
    List<ScholarshipCancelRule> findAllByScope(@Param("year") short year,
                                               @Param("academyId") Long academyId);
}
