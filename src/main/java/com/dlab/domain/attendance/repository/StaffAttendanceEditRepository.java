package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.StaffAttendanceEdit;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StaffAttendanceEditRepository extends JpaRepository<StaffAttendanceEdit, Long> {

    /** 그 기록을 몇 번 고쳤나 — 상세 화면이 「보정됨」 표시에 쓴다. */
    @Query("""
            SELECT e FROM StaffAttendanceEdit e
            WHERE e.attendance.id = :attendanceId AND e.deleted = false
            ORDER BY e.id DESC
            """)
    List<StaffAttendanceEdit> findByAttendance(@Param("attendanceId") Long attendanceId);

    /**
     * 이 기록들 중 <b>고쳐진 것</b>의 id — 목록이 「보정됨」을 표시하는 데 쓴다.
     *
     * <p>행마다 이력을 조회하면 50줄짜리 화면에 쿼리가 50번 나간다. <b>한 번에 받아</b>
     * 포함 여부만 본다.
     */
    @Query("""
            SELECT DISTINCT e.attendance.id FROM StaffAttendanceEdit e
            WHERE e.attendance.id IN :attendanceIds AND e.deleted = false
            """)
    List<Long> findEditedIds(@Param("attendanceIds") java.util.Collection<Long> attendanceIds);

    /** 지점·기간 수정 이력 — 관리자 웹 12번의 「이력 보기」. */
    @Query("""
            SELECT e FROM StaffAttendanceEdit e
            JOIN FETCH e.attendance a
            JOIN FETCH a.enrollment en
            JOIN FETCH en.student
            WHERE e.academy.id = :academyId
              AND a.workDate >= :from AND a.workDate <= :to
              AND e.deleted = false
            ORDER BY e.id DESC
            """)
    List<StaffAttendanceEdit> search(@Param("academyId") Long academyId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);
}
