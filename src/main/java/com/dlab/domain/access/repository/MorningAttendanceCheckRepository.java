package com.dlab.domain.access.repository;

import com.dlab.domain.access.entity.MorningAttendanceCheck;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MorningAttendanceCheckRepository
        extends JpaRepository<MorningAttendanceCheck, Long> {

    Optional<MorningAttendanceCheck> findByAcademyIdAndCheckDate(Long academyId,
                                                                 LocalDate checkDate);

    /**
     * 관리자 조회 — 기간·지점.
     *
     * <p>지점을 비우면 전 지점이다(본사가 아침에 전 지점을 한눈에 본다).
     */
    @Query("""
            SELECT c FROM MorningAttendanceCheck c
            JOIN FETCH c.academy
            WHERE c.checkDate >= :from AND c.checkDate <= :to
              AND (:academyId IS NULL OR c.academy.id = :academyId)
              AND c.deleted = false
            ORDER BY c.checkDate DESC, c.academy.id
            """)
    List<MorningAttendanceCheck> search(@Param("academyId") Long academyId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
