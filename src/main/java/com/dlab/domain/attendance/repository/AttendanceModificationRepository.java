package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceModification;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceModificationRepository
        extends JpaRepository<AttendanceModification, Long> {

    /** 화면이 "이 학생의 이 날"로 이력을 편다. 최신이 위다. */
    List<AttendanceModification> findByEnrollmentIdAndAttendanceDateOrderByCreatedAtDesc(
            Long enrollmentId, LocalDate attendanceDate);

    /** 감사 조회 — 지점의 기간 내 정정 전체. */
    List<AttendanceModification> findByAcademyIdAndAttendanceDateBetweenOrderByCreatedAtDesc(
            Long academyId, LocalDate from, LocalDate to);
}
