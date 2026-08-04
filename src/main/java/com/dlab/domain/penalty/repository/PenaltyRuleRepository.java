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
}
