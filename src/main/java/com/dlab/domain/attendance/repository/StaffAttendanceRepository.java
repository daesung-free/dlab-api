package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.StaffAttendance;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, Long> {

    /** 그날 마지막 기록. 출퇴근 토글이 이 값의 반대를 찍는다. */
    @Query("""
            SELECT a FROM StaffAttendance a
            WHERE a.enrollment.id = :enrollmentId AND a.workDate = :workDate
              AND a.deleted = false
            ORDER BY a.recordedAt DESC, a.id DESC
            LIMIT 1
            """)
    Optional<StaffAttendance> findLastOfDay(@Param("enrollmentId") Long enrollmentId,
                                            @Param("workDate") LocalDate workDate);

    /** 관리자 조회 — 지점·기간. 직원별로 묶는 건 표현 계층이 한다. */
    @Query("""
            SELECT a FROM StaffAttendance a
            JOIN FETCH a.enrollment e
            JOIN FETCH e.student
            WHERE a.academy.id = :academyId
              AND a.workDate >= :from AND a.workDate <= :to
              AND (:enrollmentId IS NULL OR e.id = :enrollmentId)
              AND a.deleted = false
            ORDER BY a.workDate DESC, a.recordedAt
            """)
    List<StaffAttendance> search(@Param("academyId") Long academyId,
                                 @Param("enrollmentId") Long enrollmentId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);
}
