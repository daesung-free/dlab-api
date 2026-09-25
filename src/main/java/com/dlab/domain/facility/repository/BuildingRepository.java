package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.Building;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    /** 관리 화면 목록 — 비활성도 내린다(다시 켤 수 있어야 한다). */
    @Query("""
            SELECT b FROM Building b
            WHERE b.academy.id = :academyId AND b.deleted = false
            ORDER BY b.sortOrder ASC, b.code ASC
            """)
    List<Building> findAllByAcademyId(Long academyId);

    @Query("""
            SELECT b FROM Building b
            WHERE b.academy.id = :academyId AND b.code = :code AND b.deleted = false
            """)
    Optional<Building> findByAcademyIdAndCode(Long academyId, String code);

    /**
     * 관을 지정하지 않고 구역을 만들 때 붙일 곳.
     *
     * <p>마이그레이션이 지점마다 {@code MAIN} 을 하나씩 만들어 뒀다. <b>화면이 관을 아직
     * 안 보내도 기존처럼 동작해야 하므로</b> 이 값이 필요하다 — 없으면 구역 등록이 통째로
     * 막혀 좌석 화면 셋이 다시 멈춘다.
     */
    @Query("""
            SELECT b FROM Building b
            WHERE b.academy.id = :academyId AND b.seatCdOffset = 0 AND b.deleted = false
            ORDER BY b.sortOrder ASC, b.id ASC
            """)
    List<Building> findMainCandidates(Long academyId);

    @Query("""
            SELECT COUNT(a) FROM StudyArea a
            WHERE a.building.id = :buildingId AND a.deleted = false
            """)
    long countAreas(Long buildingId);
}
