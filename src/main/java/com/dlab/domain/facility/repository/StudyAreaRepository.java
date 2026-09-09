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

    /**
     * 관리자 구역 목록 — <b>비활성 구역도 포함</b>한다.
     *
     * <p>{@link #findActiveByAcademyId(Long)}는 화면에 뿌릴 목록이라 비활성을 뺀다. 관리
     * 화면에서까지 빼면 <b>한 번 비활성으로 돌린 구역을 다시 켤 방법이 없어진다.</b>
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.deleted = false
            ORDER BY a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findAllByAcademyId(Long academyId);

    /**
     * 지운 구역까지 본다.
     *
     * <p>{@code uq_study_area}가 부분 인덱스가 아니라서 <b>지운 구역의 코드도 계속 자리를
     * 차지한다.</b> 같은 코드로 다시 등록하려면 그 행을 되살려야 하므로 삭제분도 찾는다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId AND a.areaCd = :areaCd
            """)
    Optional<StudyArea> findAnyByAcademyIdAndAreaCd(Long academyId, String areaCd);
}
