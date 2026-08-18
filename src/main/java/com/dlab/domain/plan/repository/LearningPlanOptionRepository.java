package com.dlab.domain.plan.repository;

import com.dlab.domain.plan.entity.LearningPlanOption;
import com.dlab.domain.plan.entity.LearningPlanOptionType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningPlanOptionRepository extends JpaRepository<LearningPlanOption, Long> {

    @Query("""
            SELECT o FROM LearningPlanOption o
            WHERE o.academy.id = :academyId AND o.year = :year AND o.deleted = false
            ORDER BY o.optionType, o.sortOrder, o.id
            """)
    List<LearningPlanOption> findAll(@Param("academyId") Long academyId, @Param("year") short year);

    @Query("""
            SELECT o FROM LearningPlanOption o
            WHERE o.academy.id = :academyId AND o.year = :year
              AND o.optionType = :type AND o.deleted = false
            ORDER BY o.sortOrder, o.id
            """)
    List<LearningPlanOption> findByType(@Param("academyId") Long academyId,
                                        @Param("year") short year,
                                        @Param("type") LearningPlanOptionType type);

    @Query("""
            SELECT o FROM LearningPlanOption o
            WHERE o.academy.id = :academyId AND o.year = :year
              AND o.optionType = :type AND o.label = :label AND o.deleted = false
            """)
    Optional<LearningPlanOption> findByLabel(@Param("academyId") Long academyId,
                                             @Param("year") short year,
                                             @Param("type") LearningPlanOptionType type,
                                             @Param("label") String label);
}
