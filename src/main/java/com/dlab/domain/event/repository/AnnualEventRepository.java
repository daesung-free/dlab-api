package com.dlab.domain.event.repository;

import com.dlab.domain.event.entity.AnnualEvent;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnnualEventRepository extends JpaRepository<AnnualEvent, Long> {

    /**
     * 그 해 행사. <b>지점 행사와 전 지점 공통을 합쳐서</b> 본다 — 공휴일과 같은 규칙이다.
     */
    @Query("""
            SELECT e FROM AnnualEvent e
            WHERE e.year = :year
              AND e.deleted = false
              AND (e.academyId IS NULL OR e.academyId = :academyId)
            ORDER BY e.startDate, e.id
            """)
    List<AnnualEvent> findAllOfYear(short year, Long academyId);

    /**
     * 기간에 걸치는 행사.
     *
     * <p>★ <b>기간이 "겹치는" 것을 찾는다.</b> 시작일만 비교하면 <b>3일짜리 행사의
     * 2·3일차가 빠진다</b> — 주간 학습계획이 정확히 그 경계에 걸린다.
     */
    @Query("""
            SELECT e FROM AnnualEvent e
            WHERE e.year = :year
              AND e.deleted = false
              AND e.showInPlan = true
              AND (e.academyId IS NULL OR e.academyId = :academyId)
              AND e.startDate <= :to
              AND e.endDate >= :from
            ORDER BY e.startDate, e.id
            """)
    List<AnnualEvent> findInPeriod(short year, Long academyId, LocalDate from, LocalDate to);
}
