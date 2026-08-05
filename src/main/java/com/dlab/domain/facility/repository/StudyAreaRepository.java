package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.StudyArea;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 자습 구역.
 *
 * <p>키오스크 DSA 호환 계약({@code getStudyAreaInfo}·{@code getStudyAreaSeatInfo})이
 * <b>{@code area_cd}로 조회</b>하기 때문에 코드 기반 조회가 필요하다 —
 * 키오스크는 우리 내부 id를 모른다.
 */
public interface StudyAreaRepository extends JpaRepository<StudyArea, Long> {

    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.active = true
              AND a.deleted = false
            ORDER BY a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findActiveByAcademyId(Long academyId);

    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.areaCd = :areaCd
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndAreaCd(Long academyId, String areaCd);
}
