package com.dlab.domain.consult.repository;

import com.dlab.domain.consult.entity.ConsultTag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultTagRepository extends JpaRepository<ConsultTag, Long> {

    /** 활성 태그만. 담임 화면이 쓴다. */
    @Query("""
            SELECT t FROM ConsultTag t
            WHERE t.academy.id = :academyId AND t.year = :year
              AND t.active = true AND t.deleted = false
            ORDER BY t.sortOrder ASC, t.id ASC
            """)
    List<ConsultTag> findActive(@Param("academyId") Long academyId, @Param("year") short year);

    /** 관리자 목록 — 꺼둔 것도 보인다. */
    @Query("""
            SELECT t FROM ConsultTag t
            WHERE t.academy.id = :academyId AND t.year = :year AND t.deleted = false
            ORDER BY t.sortOrder ASC, t.id ASC
            """)
    List<ConsultTag> findAll(@Param("academyId") Long academyId, @Param("year") short year);
}
