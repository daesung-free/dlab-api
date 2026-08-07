package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealPolicyRepository extends JpaRepository<MealPolicy, Long> {

    @Query("""
            SELECT p FROM MealPolicy p
            WHERE p.academy.id = :academyId AND p.year = :year AND p.deleted = false
            """)
    Optional<MealPolicy> find(@Param("academyId") Long academyId, @Param("year") short year);
}
