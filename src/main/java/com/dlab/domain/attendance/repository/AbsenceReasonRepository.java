package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AbsenceReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AbsenceReasonRepository extends JpaRepository<AbsenceReason, Long> {

    List<AbsenceReason> findByEnrollmentIdAndAttendanceDate(Long enrollmentId, LocalDate attendanceDate);

    List<AbsenceReason> findByAcademyIdAndAttendanceDate(Long academyId, LocalDate attendanceDate);

    /**
     * 등록 건의 기간 내 사유신청. 키오스크 {@code getRequestListStd}가 월 단위로 쓴다.
     */
    @Query("""
            SELECT r FROM AbsenceReason r
            WHERE r.enrollment.id = :enrollmentId
              AND r.attendanceDate >= :from
              AND r.attendanceDate <= :to
              AND r.deleted = false
            ORDER BY r.attendanceDate ASC
            """)
    List<AbsenceReason> findByEnrollmentAndPeriod(@Param("enrollmentId") Long enrollmentId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** 지점의 기간 내 사유신청. 관리자 목록이 쓴다. */
    @Query("""
            SELECT r FROM AbsenceReason r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            LEFT JOIN FETCH r.approvalRequest ar
            LEFT JOIN FETCH ar.approvalItem
            WHERE r.academy.id = :academyId
              AND r.attendanceDate >= :from
              AND r.attendanceDate <= :to
              AND r.deleted = false
            ORDER BY r.submittedAt DESC
            """)
    List<AbsenceReason> findByAcademyAndPeriod(@Param("academyId") Long academyId,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);
}
