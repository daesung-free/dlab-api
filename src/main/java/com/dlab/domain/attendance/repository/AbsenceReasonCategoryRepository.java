package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AbsenceReasonCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AbsenceReasonCategoryRepository extends JpaRepository<AbsenceReasonCategory, Long> {

    /**
     * 그 지점에서 쓰는 카테고리 — <b>전 지점 공통 + 그 지점 것</b>을 합쳐서 준다.
     *
     * <p>지점 조건만 걸면 공통 카테고리가 빠져 드롭다운이 비고, 공통만 걸면
     * 지점 고유 항목이 안 보인다.
     */
    @Query("""
            SELECT c FROM AbsenceReasonCategory c
            WHERE c.deleted = false
              AND c.year = :year
              AND (c.academy IS NULL OR c.academy.id = :academyId)
              AND (:activeOnly = false OR c.active = true)
            ORDER BY c.sortOrder, c.id
            """)
    List<AbsenceReasonCategory> findUsable(@Param("academyId") Long academyId,
                                           @Param("year") short year,
                                           @Param("activeOnly") boolean activeOnly);

    @Query("SELECT c FROM AbsenceReasonCategory c WHERE c.id = :id AND c.deleted = false")
    Optional<AbsenceReasonCategory> findActiveById(@Param("id") Long id);
}
