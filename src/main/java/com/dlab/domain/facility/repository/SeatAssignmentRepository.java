package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.SeatAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatAssignmentRepository extends JpaRepository<SeatAssignment, Long> {

    /** 이 학생의 현재 좌석. released_at IS NULL이 "배정 중"이다. */
    @Query("""
            SELECT a FROM SeatAssignment a
            JOIN FETCH a.seat s
            JOIN FETCH s.studyArea
            WHERE a.enrollment.id = :enrollmentId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    Optional<SeatAssignment> findActiveByEnrollmentId(Long enrollmentId);

    /** 이 좌석의 현재 사용자. 배정 전 점유 여부 확인에 쓴다. */
    @Query("""
            SELECT a FROM SeatAssignment a
            WHERE a.seat.id = :seatId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    Optional<SeatAssignment> findActiveBySeatId(Long seatId);

    /** 구역별 현재 배정 현황. 좌석배치도 렌더링용. */
    @Query("""
            SELECT a FROM SeatAssignment a
            JOIN FETCH a.seat s
            JOIN FETCH a.enrollment e
            JOIN FETCH e.student
            WHERE s.studyArea.id = :studyAreaId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    List<SeatAssignment> findActiveByStudyAreaId(Long studyAreaId);

    /**
     * 여러 등록 건의 현재 좌석을 <b>한 번에</b> 가져온다.
     *
     * <p>명단 화면이 행마다 {@link #findActiveByEnrollmentId}를 부르면 학생 수만큼 쿼리가 나간다.
     * 키오스크 {@code getStdInfoList}가 {@link #findActiveByAcademyId}로 푸는 것과 같은 방식이되,
     * 대상이 반 단위라 등록 건 id로 좁힌다.
     */
    @Query("""
            SELECT a FROM SeatAssignment a
            JOIN FETCH a.seat
            WHERE a.enrollment.id IN :enrollmentIds AND a.releasedAt IS NULL AND a.deleted = false
            """)
    List<SeatAssignment> findActiveByEnrollmentIds(java.util.Collection<Long> enrollmentIds);

    /** 지점의 현재 배정 전체. 키오스크 {@code getStdInfoList}가 학생별 좌석코드를 붙이는 데 쓴다. */
    @Query("""
            SELECT a FROM SeatAssignment a
            JOIN FETCH a.seat
            WHERE a.academy.id = :academyId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    List<SeatAssignment> findActiveByAcademyId(Long academyId);
}
