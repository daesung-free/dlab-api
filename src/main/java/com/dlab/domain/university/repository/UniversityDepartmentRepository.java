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

    /**
     * ⚠️ <b>{@code keyword} 에 {@code null} 을 넣지 말 것 — 빈 문자열을 넣는다.</b>
     *
     * <p>{@code :keyword IS NULL OR ... LIKE} 로 짰더니 키워드를 <b>안 넣은 호출에서만</b>
     * 500 이 났다 — PostgreSQL 이 null 바인드의 타입을 못 정해 {@code bytea} 로 추론하고
     * {@code operator does not exist: character varying ~~ bytea} 로 거절한다.
     * {@code LIKE '%%'} 는 전체와 맞으므로 분기 자체가 필요 없다.
     */
    @Query("""
            SELECT d FROM UniversityDepartment d
            WHERE d.year = :year
              AND d.universityCode = :universityCode
              AND d.deleted = false
              AND d.name LIKE CONCAT('%', :keyword, '%')
            ORDER BY d.name ASC
            """)
    List<UniversityDepartment> search(Short year, String universityCode, String keyword);

    List<UniversityDepartment> findByYearAndDeletedFalse(Short year);
}
