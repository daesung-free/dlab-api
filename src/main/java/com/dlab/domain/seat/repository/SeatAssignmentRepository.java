package com.dlab.domain.seat.repository;

import com.dlab.domain.seat.entity.SeatAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatAssignmentRepository extends JpaRepository<SeatAssignment, Long> {

    /** 지점의 현재 배정 전체. {@code getStdInfoList}가 학생별 {@code seat_cd}를 붙이는 데 쓴다. */
    @Query("""
            SELECT sa FROM SeatAssignment sa
            JOIN FETCH sa.seat
            WHERE sa.academy.id = :academyId
              AND sa.releasedAt IS NULL
              AND sa.deleted = false
            """)
    List<SeatAssignment> findActiveByAcademyId(@Param("academyId") Long academyId);

    /** 구역의 현재 배정. 좌석 상태({@code state}) 판정에 쓴다. */
    @Query("""
            SELECT sa FROM SeatAssignment sa
            JOIN FETCH sa.seat s
            JOIN FETCH sa.enrollment
            WHERE s.studyArea.id = :studyAreaId
              AND sa.releasedAt IS NULL
              AND sa.deleted = false
            """)
    List<SeatAssignment> findActiveByStudyAreaId(@Param("studyAreaId") Long studyAreaId);

    @Query("""
            SELECT sa FROM SeatAssignment sa
            JOIN FETCH sa.seat
            WHERE sa.enrollment.id = :enrollmentId
              AND sa.releasedAt IS NULL
              AND sa.deleted = false
            """)
    Optional<SeatAssignment> findActiveByEnrollmentId(@Param("enrollmentId") Long enrollmentId);

    /** 그 좌석의 현재 배정. 좌석 변경 시 선점 여부를 본다. */
    @Query("""
            SELECT sa FROM SeatAssignment sa
            JOIN FETCH sa.enrollment
            WHERE sa.seat.id = :seatId
              AND sa.releasedAt IS NULL
              AND sa.deleted = false
            """)
    Optional<SeatAssignment> findActiveBySeatId(@Param("seatId") Long seatId);
}
