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
            JOIN FETCH a.building b
            WHERE a.academy.id = :academyId
              AND a.active = true
              AND a.deleted = false
            ORDER BY b.sortOrder ASC, a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findActiveByAcademyId(Long academyId);

    /**
     * ★ 키오스크 조회 전용 — {@code kiosk_area_cd}로 찾는다.
     *
     * <p>단말이 아는 코드는 우리 {@code area_cd}가 아니라 <b>관까지 반영된 변환값</b>이다.
     * 본관은 둘이 같아서 이 구분이 티가 안 나지만, 별관을 {@code area_cd}로 찾으면
     * 본관 구역이 잡힌다 — 좌석이 통째로 다른 관 것으로 내려간다.
     */
    @Query("""
            SELECT a FROM StudyArea a
            WHERE a.academy.id = :academyId
              AND a.kioskAreaCd = :kioskAreaCd
              AND a.deleted = false
            """)
    Optional<StudyArea> findByAcademyIdAndKioskAreaCd(Long academyId, String kioskAreaCd);

    /**
     * 관리자 구역 목록 — <b>비활성 구역도 포함</b>한다.
     *
     * <p>{@link #findActiveByAcademyId(Long)}는 화면에 뿌릴 목록이라 비활성을 뺀다. 관리
     * 화면에서까지 빼면 <b>한 번 비활성으로 돌린 구역을 다시 켤 방법이 없어진다.</b>
     */
    @Query("""
            SELECT a FROM StudyArea a
            JOIN FETCH a.building b
            WHERE a.academy.id = :academyId
              AND (:buildingId IS NULL OR b.id = :buildingId)
              AND a.deleted = false
            ORDER BY b.sortOrder ASC, a.sortOrder ASC, a.areaCd ASC
            """)
    List<StudyArea> findAllByAcademyId(Long academyId, Long buildingId);

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
