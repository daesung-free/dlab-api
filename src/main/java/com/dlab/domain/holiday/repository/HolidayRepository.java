package com.dlab.domain.holiday.repository;

import com.dlab.domain.holiday.entity.Holiday;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    /**
     * 기간 내 휴일. <b>전 지점 공통(academyId is null) + 해당 지점 것</b>을 함께 가져온다.
     * 지점 조건을 빠뜨리면 다른 지점 개원기념일에 급식이 막힌다.
     */
    @Query("""
            SELECT h FROM Holiday h
            WHERE h.deleted = false
              AND h.holidayDate BETWEEN :from AND :to
              AND (h.academyId IS NULL OR h.academyId = :academyId)
            """)
    List<Holiday> findInRange(@Param("academyId") Long academyId,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    /**
     * 전 지점 것을 모두. <b>본사 화면용</b>이다.
     *
     * <p>지점 계정은 {@link #findInRange}로 "공통 + 내 지점"을 보고, 본사는 여기로
     * <b>모든 지점</b>을 본다 — 다른 목록이 전부 그렇게 동작한다.
     */
    @Query("""
            SELECT h FROM Holiday h
            WHERE h.deleted = false
              AND h.holidayDate BETWEEN :from AND :to
            """)
    List<Holiday> findAllInRange(@Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /** 전 지점 공통 휴일만. {@code academyId}가 없는 호출(배치 등)에 쓴다. */
    @Query("""
            SELECT h FROM Holiday h
            WHERE h.deleted = false
              AND h.holidayDate BETWEEN :from AND :to
              AND h.academyId IS NULL
            """)
    List<Holiday> findNationwideInRange(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
