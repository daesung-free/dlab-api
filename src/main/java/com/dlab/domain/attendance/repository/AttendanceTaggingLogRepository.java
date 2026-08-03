package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AttendanceTaggingLogRepository extends JpaRepository<AttendanceTaggingLog, Long> {

    List<AttendanceTaggingLog> findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(
            Long enrollmentId, LocalDate attendanceDate);

    List<AttendanceTaggingLog> findByAcademyIdAndAttendanceDate(Long academyId, LocalDate attendanceDate);
}
