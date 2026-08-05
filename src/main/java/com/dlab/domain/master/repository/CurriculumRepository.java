package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.Curriculum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CurriculumRepository extends JpaRepository<Curriculum, Long> {

    @Query("""
            SELECT c FROM Curriculum c
              LEFT JOIN FETCH c.classMaster
            WHERE c.deleted = false AND c.academy.id = :academyId AND c.year = :year
            ORDER BY c.sortOrder ASC, c.name ASC
            """)
    List<Curriculum> findAllOfYear(Long academyId, short year);

    boolean existsByAcademyIdAndYearAndNameAndDeletedFalse(Long academyId, short year, String name);
}
