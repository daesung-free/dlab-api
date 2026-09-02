package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.TuitionMonth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TuitionMonthRepository extends JpaRepository<TuitionMonth, Long> {

    /** 지점 행이 있으면 그것만, 없으면 공통본. {@code TuitionPriceRepository}와 같은 규칙이다. */
    @Query("""
            SELECT m FROM TuitionMonth m
            WHERE m.year = :year AND m.month = :month AND m.deleted = false
              AND (
                    m.academy.id = :academyId
                 OR (m.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM TuitionMonth o
                        WHERE o.year = :year AND o.month = :month
                          AND o.academy.id = :academyId AND o.deleted = false))
              )
            """)
    Optional<TuitionMonth> findApplicable(@Param("year") short year,
                                          @Param("month") short month,
                                          @Param("academyId") Long academyId);

    @Query("""
            SELECT m FROM TuitionMonth m
            WHERE m.year = :year AND m.deleted = false
              AND (:academyId IS NULL AND m.academy IS NULL OR m.academy.id = :academyId)
            ORDER BY m.month
            """)
    List<TuitionMonth> findAllByScope(@Param("year") short year,
                                      @Param("academyId") Long academyId);
}
