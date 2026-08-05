package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.ClassMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClassMasterRepository extends JpaRepository<ClassMaster, Long> {

    /**
     * 지점·연도별 반 목록.
     * {@code academyId}가 null이면 전 지점(상위 관리자) — SearchScope 규약과 같다.
     */
    @Query("""
            SELECT c FROM ClassMaster c
            LEFT JOIN FETCH c.homeroomTeacher
            WHERE c.deleted = false
              AND (:academyId IS NULL OR c.academy.id = :academyId)
              AND (:year IS NULL OR c.year = :year)
            ORDER BY c.name ASC
            """)
    List<ClassMaster> search(Long academyId, Short year);

    boolean existsByAcademyIdAndYearAndName(Long academyId, short year, String name);

    @Query("""
            SELECT c FROM ClassMaster c
            LEFT JOIN FETCH c.homeroomTeacher
            LEFT JOIN FETCH c.academy
            WHERE c.id = :id AND c.deleted = false
            """)
    Optional<ClassMaster> findDetailById(Long id);
}
