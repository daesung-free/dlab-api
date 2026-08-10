package com.dlab.domain.routine.repository;

import com.dlab.domain.routine.entity.DailyRoutine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DailyRoutineRepository extends JpaRepository<DailyRoutine, Long> {

    List<DailyRoutine> findByAcademyIdAndYearAndMonthAndDeletedFalseOrderBySortOrderAscIdAsc(
            Long academyId, short year, short month);

    boolean existsByAcademyIdAndYearAndMonthAndDeletedFalse(Long academyId, short year, short month);

    /**
     * 학생에게 적용되는 그달 루틴.
     *
     * <p><b>반 지정분 + 지점 공통분을 함께</b> 본다. 반 전용만 보면 지점 공통 루틴이
     * 학생 앱에서 사라지고, 공통만 보면 반별 시험지가 안 나온다.
     */
    @Query("""
            SELECT r FROM DailyRoutine r
            WHERE r.academy.id = :academyId
              AND r.year = :year AND r.month = :month
              AND r.deleted = false
              AND (r.classMaster IS NULL OR r.classMaster.id IN :classIds)
            ORDER BY r.recommended DESC, r.sortOrder, r.id
            """)
    List<DailyRoutine> findForStudent(@Param("academyId") Long academyId,
                                      @Param("year") short year,
                                      @Param("month") short month,
                                      @Param("classIds") List<Long> classIds);
}
