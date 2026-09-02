package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.SeatMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatMasterRepository extends JpaRepository<SeatMaster, Long> {

    @Query("""
            SELECT s FROM SeatMaster s
            JOIN FETCH s.studyArea
            JOIN FETCH s.academy
            WHERE s.id = :id AND s.deleted = false
            """)
    Optional<SeatMaster> findDetailById(Long id);

    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId AND s.deleted = false
            ORDER BY s.yPos ASC, s.xPos ASC
            """)
    List<SeatMaster> findByStudyAreaId(Long studyAreaId);

    /**
     * DSA {@code seat_cd}로 찾는다 — 키오스크가 좌석 변경({@code setSeatChgProc})에서
     * 내부 id가 아니라 이 코드를 보낸다.
     */
    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.academy.id = :academyId
              AND s.seatCd = :seatCd
              AND s.deleted = false
            """)
    java.util.Optional<SeatMaster> findByAcademyIdAndSeatCd(Long academyId, String seatCd);

    /**
     * 지운 좌석까지 포함해 코드로 찾는다.
     *
     * <p>{@code uq_seat_master}가 <b>부분 인덱스가 아니라</b> 삭제된 좌석의 코드도 계속
     * 자리를 차지한다. 등록 전에 이걸로 확인하지 않으면 DB 제약 위반이 그대로 500으로
     * 올라가고, 화면에는 이유가 안 보인다.
     */
    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.academy.id = :academyId AND s.seatCd IN :seatCds
            """)
    List<SeatMaster> findAnyByAcademyIdAndSeatCdIn(Long academyId, List<String> seatCds);

    /** 구역에 남아 있는 좌석 수. 구역 삭제 가능 여부 판정용. */
    @Query("""
            SELECT COUNT(s) FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId AND s.deleted = false
            """)
    long countByStudyAreaId(Long studyAreaId);
}
