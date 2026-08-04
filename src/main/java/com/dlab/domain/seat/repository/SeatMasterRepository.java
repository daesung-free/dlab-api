package com.dlab.domain.seat.repository;

import com.dlab.domain.seat.entity.SeatMaster;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatMasterRepository extends JpaRepository<SeatMaster, Long> {

    /**
     * 구역의 좌석 전체.
     *
     * <p><b>{@code usable = false}인 좌석도 포함한다.</b> 키오스크는 사용 불가 좌석도
     * 배치도에 회색으로 그린다 — 빼버리면 자리 배열에 구멍이 생겨 학생이 자기 자리를 못 찾는다.
     */
    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId
              AND s.deleted = false
            ORDER BY s.yPos ASC, s.xPos ASC, s.seatCd ASC
            """)
    List<SeatMaster> findByStudyAreaId(@Param("studyAreaId") Long studyAreaId);
}
