package com.dlab.domain.patrol.repository;

import com.dlab.domain.patrol.entity.PatrolRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatrolRecordRepository extends JpaRepository<PatrolRecord, Long> {

    /**
     * 그날 그 회차 전체. 화면이 좌석표를 한 번에 그린다.
     *
     * <p>회차를 비우면 <b>그날 전부</b>다 — 「1~5회차를 한 화면에」 볼 때 쓴다.
     */
    @Query("""
            SELECT p FROM PatrolRecord p
            JOIN FETCH p.enrollment e
            JOIN FETCH e.student
            WHERE p.academy.id = :academyId
              AND p.patrolDate = :date
              AND (:roundNo IS NULL OR p.roundNo = :roundNo)
              AND p.deleted = false
            ORDER BY p.roundNo, p.seatCd
            """)
    List<PatrolRecord> findByRound(@Param("academyId") Long academyId,
                                   @Param("date") LocalDate date,
                                   @Param("roundNo") Short roundNo);

    /** 그 학생의 그 회차 기록. 다시 찍었을 때 덮어쓰기 위해 찾는다. */
    @Query("""
            SELECT p FROM PatrolRecord p
            WHERE p.patrolDate = :date
              AND p.roundNo = :roundNo
              AND p.enrollment.id = :enrollmentId
              AND p.deleted = false
            """)
    Optional<PatrolRecord> findOne(@Param("date") LocalDate date,
                                   @Param("roundNo") short roundNo,
                                   @Param("enrollmentId") Long enrollmentId);
}
