package com.dlab.domain.university.repository;

import com.dlab.domain.university.entity.University;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UniversityRepository extends JpaRepository<University, Long> {

    Optional<University> findByYearAndCodeAndDeletedFalse(Short year, String code);

    /**
     * 이름 검색.
     *
     * <p>★ <b>드롭다운이 아니라 검색이어야 한다</b> — 223개라 목록으로는 못 찾는다.
     * 캠퍼스가 이름에 있어(「연세대(미래-원주)」) 「연세」만 넣어도 캠퍼스가 다 나온다.
     */
    /**
     * ⚠️ <b>{@code keyword} 에 {@code null} 을 넣지 말 것 — 빈 문자열을 넣는다.</b>
     *
     * <p>{@code :keyword IS NULL OR ... LIKE} 로 짰더니 키워드를 <b>안 넣은 호출에서만</b>
     * 500 이 났다 — PostgreSQL 이 null 바인드의 타입을 못 정해 {@code bytea} 로 추론하고
     * {@code operator does not exist: character varying ~~ bytea} 로 거절한다.
     * {@code LIKE '%%'} 는 전체와 맞으므로 분기 자체가 필요 없다.
     */
    @Query("""
            SELECT u FROM University u
            WHERE u.year = :year
              AND u.deleted = false
              AND u.name LIKE CONCAT('%', :keyword, '%')
            ORDER BY u.name ASC
            """)
    List<University> search(Short year, String keyword);

    List<University> findByYearAndDeletedFalse(Short year);
}
