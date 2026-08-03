package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AttendanceDailyStatusRepository extends JpaRepository<AttendanceDailyStatus, Long> {

    Optional<AttendanceDailyStatus> findByEnrollmentIdAndAttendanceDate(Long enrollmentId, LocalDate attendanceDate);
}
