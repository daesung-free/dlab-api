package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.Tuition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TuitionRepository extends JpaRepository<Tuition, Long> {

    @Query("""
            SELECT t FROM Tuition t
            WHERE t.deleted = false AND t.academy.id = :academyId AND t.year = :year
            ORDER BY t.sortOrder ASC, t.name ASC
            """)
    List<Tuition> findAllOfYear(Long academyId, short year);

    boolean existsByAcademyIdAndYearAndNameAndDeletedFalse(Long academyId, short year, String name);
}
