package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealClosure;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealClosureRepository extends JpaRepository<MealClosure, Long> {

    @Query("""
            SELECT c FROM MealClosure c
            WHERE c.academy.id = :academyId
              AND c.closureDate >= :from AND c.closureDate <= :to
              AND c.deleted = false
            ORDER BY c.closureDate ASC
            """)
    List<MealClosure> findByAcademyAndPeriod(@Param("academyId") Long academyId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    @Query("""
            SELECT c FROM MealClosure c
            WHERE c.academy.id = :academyId AND c.closureDate = :date AND c.deleted = false
            """)
    Optional<MealClosure> findByAcademyAndDate(@Param("academyId") Long academyId,
                                               @Param("date") LocalDate date);
}
