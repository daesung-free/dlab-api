package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealOrderWindow;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealOrderWindowRepository extends JpaRepository<MealOrderWindow, Long> {

    @Query("""
            SELECT w FROM MealOrderWindow w
            WHERE w.academy.id = :academyId AND w.targetMonth = :targetMonth AND w.deleted = false
            """)
    Optional<MealOrderWindow> find(@Param("academyId") Long academyId,
                                   @Param("targetMonth") LocalDate targetMonth);

    @Query("""
            SELECT w FROM MealOrderWindow w
            WHERE w.academy.id = :academyId AND w.year = :year AND w.deleted = false
            ORDER BY w.targetMonth ASC
            """)
    List<MealOrderWindow> findByYear(@Param("academyId") Long academyId,
                                     @Param("year") short year);
}
