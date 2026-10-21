package com.dlab.domain.university.repository;

import com.dlab.domain.university.entity.UniversityDepartment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UniversityDepartmentRepository extends JpaRepository<UniversityDepartment, Long> {

    /** ★ 대학코드까지 봐야 유일하다 — 학과코드 503개가 여러 대학에서 쓰인다. */
    Optional<UniversityDepartment> findByYearAndUniversityCodeAndCodeAndDeletedFalse(
            Short year, String universityCode, String code);

    @Query("""
            SELECT d FROM UniversityDepartment d
            WHERE d.year = :year
              AND d.universityCode = :universityCode
              AND d.deleted = false
              AND (:keyword IS NULL OR d.name LIKE CONCAT('%', :keyword, '%'))
            ORDER BY d.name ASC
            """)
    List<UniversityDepartment> search(Short year, String universityCode, String keyword);

    List<UniversityDepartment> findByYearAndDeletedFalse(Short year);
}
