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
              AND (:seatCds IS NULL OR p.seatCd IN :seatCds)
              AND p.deleted = false
            ORDER BY p.roundNo, p.seatCd
            """)
    List<PatrolRecord> findByRound(@Param("academyId") Long academyId,
                                   @Param("date") LocalDate date,
                                   @Param("roundNo") Short roundNo,
                                   @Param("seatCds") java.util.Collection<String> seatCds);

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

    /**
     * ★★ 그 학생이 <b>그 날 졸음으로 찍힌 회차 수</b> (디랩 1029 4-6).
     *
     * <h2>⚠️ 「교시별」이 아니라 「회차별」이다</h2>
     * 순찰 기록에 <b>교시가 없다.</b> 있는 것은 <b>순찰 회차({@code round_no}, 1~5)</b>이고
     * 스키마가 <b>{@code (날짜, 회차, 학생)} 유일</b>이라 <b>한 회차에 같은 학생이
     * 두 행일 수 없다</b> — 즉 이 수가 곧 <b>졸음으로 찍힌 회차 수</b>다.
     *
     * <p>★ 디랩이 「교시별」로 <b>순찰 회차를 뜻한 것인지 확인해야 한다.</b>
     */
    @Query("""
            select count(r)
              from PatrolRecord r
             where r.enrollment.id = :enrollmentId
               and r.patrolDate = :date
               and r.mark = :mark
               and r.deleted = false
            """)
    long countByMark(@Param("enrollmentId") Long enrollmentId,
                     @Param("date") java.time.LocalDate date,
                     @Param("mark") com.dlab.domain.patrol.entity.PatrolMark mark);
}
