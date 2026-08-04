package com.dlab.domain.seat.repository;

import com.dlab.domain.seat.entity.StudyArea;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudyAreaRepository extends JpaRepository<StudyArea, Long> {

    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.active = true
              AND a.deleted = false
            ORDER BY a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findActiveByAcademyId(@Param("academyId") Long academyId);

    /** DSA {@code area_cd}로 찾는다 — 키오스크가 우리 내부 id를 모른다. */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.areaCd = :areaCd
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndAreaCd(@Param("academyId") Long academyId,
                                                 @Param("areaCd") String areaCd);
}
