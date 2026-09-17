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
    /**
     * 화면에 뿌릴 구역 — 비활성은 뺀다.
     *
     * <p><b>거르는 축이 둘이라 한 메서드로 묶었다.</b> 관(별관 구분)과 종류(독서실·반 교실)를
     * 각각 다른 메서드로 두면 호출부가 <b>한쪽만 건 반쪽짜리를 고르기 쉽다</b> — 그러면
     * 독서실 목록에 반이 섞이거나, 별관 구역이 본관 목록에 뜬다.
     * 둘 다 {@code null}이면 안 건다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            JOIN FETCH a.building b
            WHERE a.academy.id = :academyId
              AND (:buildingId IS NULL OR b.id = :buildingId)
              AND (:areaType IS NULL OR a.areaType = :areaType)
              AND a.active = true
              AND a.deleted = false
            ORDER BY b.sortOrder ASC, a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findActive(Long academyId, Long buildingId, AreaType areaType);

    /**
     * 관리 화면 구역 — <b>비활성도 포함</b>한다.
     *
     * <p>{@link #findActive}는 화면에 뿌릴 목록이라 비활성을 뺀다. 관리 화면에서까지 빼면
     * <b>한 번 끈 구역을 다시 켤 방법이 없어진다.</b>
     */
    @Query("""
            SELECT a FROM StudyArea a
            JOIN FETCH a.building b
            WHERE a.academy.id = :academyId
              AND (:buildingId IS NULL OR b.id = :buildingId)
              AND (:areaType IS NULL OR a.areaType = :areaType)
              AND a.deleted = false
            ORDER BY b.sortOrder ASC, a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findAll(Long academyId, Long buildingId, AreaType areaType);


    /** 반에 붙은 좌석표. 반 하나에 하나뿐이다({@code uq_study_area_class}). */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.classMaster.id = :classMasterId
              AND a.deleted = false
            """)
    Optional<StudyArea> findByClassMasterId(Long classMasterId);

    /**
     * ★ 키오스크 조회 전용 — <b>{@code kiosk_area_cd} + 종류</b> 둘 다 건다.
     *
     * <p><b>코드</b>: 단말이 아는 값은 우리 {@code area_cd}가 아니라 관까지 반영된
     * 변환값이다. 본관은 둘이 같아 티가 안 나지만, 별관을 {@code area_cd}로 찾으면
     * 본관 구역이 잡혀 <b>좌석이 통째로 다른 관 것으로 내려간다.</b>
     *
     * <p><b>종류</b>: 교실이 섞여 내려가면 단말 좌석 화면에 반이 뜬다.
     *
     * <p>둘 중 하나만 걸면 각각 다른 사고가 난다 — 그래서 한 메서드로 묶었다.
     * 나눠 두면 호출부가 반쪽짜리를 고르기 쉽다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.kioskAreaCd = :kioskAreaCd
              AND a.areaType = :areaType
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndKioskAreaCdAndType(Long academyId, String kioskAreaCd,
                                                             AreaType areaType);

    /**
     * 등록할 때 키오스크 코드가 이미 쓰이는지 본다.
     *
     * <p><b>종류를 가리지 않는다</b> — 교실이 쓰는 코드와도 겹치면 안 된다.
     * 단말은 종류를 모르고 코드 하나로만 찾는다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.kioskAreaCd = :kioskAreaCd
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndKioskAreaCd(Long academyId, String kioskAreaCd);

    /**
     * 지운 구역까지 본다 — <b>관 안에서</b> 찾는다.
     *
     * <p>유니크가 부분 인덱스가 된 뒤로 삭제분이 코드를 붙들지는 않지만, 같은 코드로 다시
     * 등록하면 <b>그 행을 되살리는 편이 낫다</b> — 새로 만들면 좌석이 딸린 옛 구역이
     * 떠다니게 된다.
     *
     * <p>살아 있는 행이 먼저다. 삭제분이 여러 개 쌓여 있을 수 있어 정렬로 고정한다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.building.id = :buildingId AND a.areaCd = :areaCd
            ORDER BY a.deleted ASC, a.id DESC
            """)
    List<StudyArea> findAnyByBuildingIdAndAreaCd(Long buildingId, String areaCd);
}
