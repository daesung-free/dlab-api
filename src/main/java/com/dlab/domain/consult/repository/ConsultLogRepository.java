package com.dlab.domain.consult.repository;

import com.dlab.domain.consult.entity.ConsultLog;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultLogRepository extends JpaRepository<ConsultLog, Long> {

    /** 학생 상세 — 최근 순. */
    @Query("""
            SELECT l FROM ConsultLog l
            LEFT JOIN FETCH l.tags t
            LEFT JOIN FETCH t.tag
            WHERE l.enrollment.id = :enrollmentId AND l.deleted = false
            ORDER BY l.consultedAt DESC, l.id DESC
            """)
    List<ConsultLog> findByEnrollment(@Param("enrollmentId") Long enrollmentId);

    /**
     * 기간별 상담 목록. 담임 필터는 서비스에서 건다.
     *
     * <p>현황 화면이 학생별 <b>최근 상담</b>을 뽑는 데도 쓴다.
     */
    @Query("""
            SELECT l FROM ConsultLog l
            JOIN FETCH l.enrollment e
            JOIN FETCH e.student
            LEFT JOIN FETCH l.teacher
            WHERE l.academy.id = :academyId AND l.year = :year
              AND l.consultedAt >= :from AND l.consultedAt <= :to
              AND l.deleted = false
            ORDER BY l.consultedAt DESC, l.id DESC
            """)
    List<ConsultLog> findByPeriod(@Param("academyId") Long academyId,
                                  @Param("year") short year,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /** 지점·연도 전체(현황 집계용). 학생별 최근 상담을 뽑는다. */
    @Query("""
            SELECT l FROM ConsultLog l
            LEFT JOIN FETCH l.teacher
            WHERE l.academy.id = :academyId AND l.year = :year AND l.deleted = false
            ORDER BY l.consultedAt DESC, l.id DESC
            """)
    List<ConsultLog> findAllByYear(@Param("academyId") Long academyId, @Param("year") short year);

    @Query("""
            SELECT l FROM ConsultLog l
            LEFT JOIN FETCH l.tags t
            LEFT JOIN FETCH t.tag
            WHERE l.id = :id AND l.deleted = false
            """)
    Optional<ConsultLog> findDetail(@Param("id") Long id);
}
