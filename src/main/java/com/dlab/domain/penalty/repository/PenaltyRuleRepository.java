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

    /** 이 항목을 쓰는 규칙. 항목 삭제 전에 확인한다 — 지우면 규칙이 없는 항목을 가리킨다. */
    List<PenaltyRule> findByPenaltyItemIdAndDeletedFalse(Long penaltyItemId);

    /** 항목 하나의 자동 부여 여부. ⚠️ <b>목록에서는 쓰지 말 것</b> — 아래 묶음 조회를 쓴다. */
    boolean existsByPenaltyItemIdAndActiveTrueAndDeletedFalse(Long penaltyItemId);

    /**
     * ★ 자동 부여 규칙이 걸린 <b>항목 id 들</b>을 한 번에 받는다.
     *
     * <p>항목마다 {@code existsBy...} 를 부르면 <b>목록 한 번에 항목 수만큼 쿼리가 난다</b> —
     * 디랩 상벌점 항목이 56개라 한 화면에 57 쿼리였다.
     */
    @Query("""
            SELECT DISTINCT r.penaltyItem.id FROM PenaltyRule r
             WHERE r.penaltyItem.id IN :itemIds AND r.active = true AND r.deleted = false
            """)
    java.util.Set<Long> findAutomaticItemIds(java.util.Collection<Long> itemIds);
}
