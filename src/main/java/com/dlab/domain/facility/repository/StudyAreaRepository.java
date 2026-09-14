package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.AreaType;
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

    /**
     * ⚠️ <b>종류를 가리지 않는다.</b> 키오스크에 내려줄 목록에는 쓰지 말 것 —
     * 반 교실이 섞여 단말 좌석 화면에 반이 뜬다. 그쪽은
     * {@link #findActiveByAcademyIdAndType(Long, AreaType)}을 쓴다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.active = true
              AND a.deleted = false
            ORDER BY a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findActiveByAcademyId(Long academyId);

    /** 종류로 거른 활성 구역. 키오스크(STUDY)와 반 좌석표 화면(CLASSROOM)이 쓴다. */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.areaType = :areaType
              AND a.active = true
              AND a.deleted = false
            ORDER BY a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findActiveByAcademyIdAndType(Long academyId, AreaType areaType);

    /** 관리 화면용 — 비활성까지 포함해 종류로 거른다. */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.areaType = :areaType
              AND a.deleted = false
            ORDER BY a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findAllByAcademyIdAndType(Long academyId, AreaType areaType);

    /** 반에 붙은 좌석표. 반 하나에 하나뿐이다({@code uq_study_area_class}). */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.classMaster.id = :classMasterId
              AND a.deleted = false
            """)
    Optional<StudyArea> findByClassMasterId(Long classMasterId);

    /**
     * ⚠️ 종류를 가리지 않는다. 키오스크 경로는
     * {@link #findByAcademyIdAndAreaCdAndType(Long, String, AreaType)}을 쓴다 —
     * 교실 코드로 좌석을 조회하면 단말에 반 좌석이 그대로 나간다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.areaCd = :areaCd
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndAreaCd(Long academyId, String areaCd);

    /** 종류까지 맞는 구역만. */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.areaCd = :areaCd
              AND a.areaType = :areaType
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndAreaCdAndType(Long academyId, String areaCd,
                                                        AreaType areaType);

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
