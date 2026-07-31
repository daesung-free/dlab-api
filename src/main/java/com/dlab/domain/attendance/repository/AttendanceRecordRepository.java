package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByStudentIdAndAttendanceDate(Long studentId, LocalDate attendanceDate);

    List<AttendanceRecord> findByBranchIdAndAttendanceDate(Long branchId, LocalDate attendanceDate);

    boolean existsByStudentIdAndAttendanceDate(Long studentId, LocalDate attendanceDate);
}
