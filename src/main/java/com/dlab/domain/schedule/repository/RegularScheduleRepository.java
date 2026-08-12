package com.dlab.domain.schedule.repository;

import com.dlab.domain.schedule.entity.RegularSchedule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegularScheduleRepository extends JpaRepository<RegularSchedule, Long> {

    @Query("""
            SELECT s FROM RegularSchedule s
            LEFT JOIN FETCH s.items i
            LEFT JOIN FETCH s.approvalRequest
            WHERE s.enrollment.id = :enrollmentId
              AND s.year = :year AND s.scheduleMonth = :month
              AND s.deleted = false AND (i IS NULL OR i.deleted = false)
            """)
    Optional<RegularSchedule> findByStudentAndMonth(@Param("enrollmentId") Long enrollmentId,
                                                    @Param("year") short year,
                                                    @Param("month") short month);

    /** 관리자 목록 — 그 달에 정기일정을 낸 학생 전체. */
    @Query("""
            SELECT DISTINCT s FROM RegularSchedule s
            JOIN FETCH s.enrollment e
            JOIN FETCH e.student
            LEFT JOIN FETCH s.items i
            LEFT JOIN FETCH s.approvalRequest
            WHERE s.academy.id = :academyId
              AND s.year = :year AND s.scheduleMonth = :month
              AND s.deleted = false AND (i IS NULL OR i.deleted = false)
            ORDER BY s.id
            """)
    List<RegularSchedule> findByAcademyAndMonth(@Param("academyId") Long academyId,
                                                @Param("year") short year,
                                                @Param("month") short month);
}
