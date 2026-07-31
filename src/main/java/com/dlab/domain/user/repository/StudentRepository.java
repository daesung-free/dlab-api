package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByPublicCode(String publicCode);

    Optional<Student> findByUserAccountId(Long userAccountId);

    /**
     * 해당 날짜에 무단 미등원인 학생 목록.
     * - 출결 기록(attendance_record)이 없고
     * - 사전 제출된 결석/지각 사유(반려된 건 제외)도 없는 학생만
     * CLAUDE.md §3 "미등원 알림은 이벤트가 없었음을 감지한다" 참고.
     */
    @Query("""
            SELECT s FROM Student s
            JOIN FETCH s.userAccount ua
            WHERE s.branch.id = :branchId
              AND ua.status = com.dlab.domain.user.entity.AccountStatus.ACTIVE
              AND NOT EXISTS (
                    SELECT 1 FROM AttendanceRecord ar
                    WHERE ar.student = s AND ar.attendanceDate = :date)
              AND NOT EXISTS (
                    SELECT 1 FROM AbsenceReason r
                    WHERE r.student = s AND r.targetDate = :date
                      AND r.status <> com.dlab.domain.attendance.entity.AbsenceReasonStatus.REJECTED)
            """)
    List<Student> findUnexcusedAbsentees(Long branchId, LocalDate date);
}
