package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AttendanceTaggingLogRepository extends JpaRepository<AttendanceTaggingLog, Long> {

    List<AttendanceTaggingLog> findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(
            Long enrollmentId, LocalDate attendanceDate);

    List<AttendanceTaggingLog> findByAcademyIdAndAttendanceDate(Long academyId, LocalDate attendanceDate);

    /** 지점의 기간 내 원장 전체. 통계·순공시간 집계가 쓴다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM AttendanceTaggingLog l
            JOIN FETCH l.enrollment e
            JOIN FETCH e.student
            WHERE l.academy.id = :academyId
              AND l.attendanceDate >= :from
              AND l.attendanceDate <= :to
              AND l.deleted = false
            ORDER BY l.recordedAt ASC
            """)
    List<AttendanceTaggingLog> findByAcademyAndPeriod(Long academyId, LocalDate from, LocalDate to);

    /** 등록 건의 기간 내 원장. 학생별 출결 특이사항(3.26)·지각 수(3.15)가 쓴다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM AttendanceTaggingLog l
            WHERE l.enrollment.id = :enrollmentId
              AND l.attendanceDate >= :from
              AND l.attendanceDate <= :to
              AND l.deleted = false
            ORDER BY l.recordedAt ASC
            """)
    List<AttendanceTaggingLog> findByEnrollmentAndPeriod(Long enrollmentId, LocalDate from, LocalDate to);
}
