package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StudentEnrollmentRepository extends JpaRepository<StudentEnrollment, Long> {

    /**
     * 카드번호로 현재 유효한 등록 건 찾기.
     * rfid_no는 UNIQUE가 아니므로(이력) is_current 필터가 필수다 —
     * 빠뜨리면 퇴원생 카드로 태깅이 통과한다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.rfidNo = :rfidNo AND e.current = true AND e.deleted = false
            """)
    Optional<StudentEnrollment> findCurrentByRfidNo(String rfidNo);

    /**
     * 학생(사람)의 현재 유효한 등록 건.
     * 신청·조회는 전부 "올해 등록 건" 기준이라 앱 요청마다 이걸로 변환한다.
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            JOIN FETCH e.academy
            WHERE e.student.id = :studentId AND e.current = true AND e.deleted = false
            """)
    Optional<StudentEnrollment> findCurrentByStudentId(Long studentId);

    /**
     * 해당 날짜에 무단 미등원인 등록 건.
     * - 그날 등원 태깅(S/A)이 없고
     * - 사전 제출된 사유도 없는 학생만 (사유를 낸 학생은 무단결석이 아니다)
     */
    @Query("""
            SELECT e FROM StudentEnrollment e
            JOIN FETCH e.student
            WHERE e.academy.id = :academyId
              AND e.current = true
              AND e.deleted = false
              AND e.enrollmentStatus = com.dlab.domain.user.entity.EnrollmentStatus.ENROLLED
              AND NOT EXISTS (
                    SELECT 1 FROM AttendanceTaggingLog t
                    WHERE t.enrollment = e AND t.attendanceDate = :date)
              AND NOT EXISTS (
                    SELECT 1 FROM AbsenceReason r
                    WHERE r.enrollment = e AND r.attendanceDate = :date AND r.deleted = false)
            """)
    List<StudentEnrollment> findUnexcusedAbsentees(Long academyId, LocalDate date);
}
