package com.dlab.domain.penalty.repository;

import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PenaltyRuleRepository extends JpaRepository<PenaltyRule, Long> {

    /**
     * 활성 규칙만. <b>{@code active = false}는 조회 자체에서 뺀다</b> —
     * 조회 후 필터링하면 어느 코드 경로가 그 필터를 빠뜨렸는지 추적하기 어렵다.
     */
    @Query("""
            SELECT r FROM PenaltyRule r
              JOIN FETCH r.penaltyItem
            WHERE r.deleted = false
              AND r.active = true
              AND r.academy.id = :academyId
              AND r.year = :year
              AND r.triggerType = :triggerType
            """)
    List<PenaltyRule> findActive(@Param("academyId") Long academyId,
                                 @Param("year") short year,
                                 @Param("triggerType") PenaltyTriggerType triggerType);

    /**
     * 해당 연도 전체 규칙. 전년도 복사가 쓴다 — 아직 켜지 않은(active=false) 규칙도
     * 그대로 넘어가야 하므로 {@link #findActive}를 재사용할 수 없다.
     */
    @Query("""
            SELECT r FROM PenaltyRule r
              JOIN FETCH r.penaltyItem
            WHERE r.deleted = false
              AND r.academy.id = :academyId
              AND r.year = :year
            """)
    List<PenaltyRule> findAllOfYear(@Param("academyId") Long academyId, @Param("year") short year);
}
