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
}
