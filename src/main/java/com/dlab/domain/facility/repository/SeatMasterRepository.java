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
}
