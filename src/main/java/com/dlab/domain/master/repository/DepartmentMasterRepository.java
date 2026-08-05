package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.DepartmentMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DepartmentMasterRepository extends JpaRepository<DepartmentMaster, Long> {

    @Query("""
            SELECT d FROM DepartmentMaster d
            WHERE d.deleted = false
              AND (:academyId IS NULL OR d.academy.id = :academyId)
              AND (:year IS NULL OR d.year = :year)
            ORDER BY d.name ASC
            """)
    List<DepartmentMaster> search(Long academyId, Short year);

    boolean existsByAcademyIdAndYearAndNameAndDeletedFalse(Long academyId, short year, String name);
}
