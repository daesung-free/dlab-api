package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.DufExamDate;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DufExamDateRepository extends JpaRepository<DufExamDate, Long> {

    /**
     * ★ 그 지점의 그 날이 <b>응시일인가</b>.
     *
     * <p>★ <b>지점 전용 행과 전 지점 공통(NULL)을 함께 본다</b> — ⚠️ 공통을 빼면
     * 전국 일정이 안 걸리고, 지점 전용을 빼면 지점이 따로 잡은 날이 안 걸린다.
     */
    @Query("""
            select count(d) > 0
              from DufExamDate d
             where d.examDate = :date
               and (d.academy is null or d.academy.id = :academyId)
               and d.deleted = false
            """)
    boolean isExamDate(@Param("academyId") Long academyId, @Param("date") LocalDate date);

    /** 관리 목록. ★ 공통과 그 지점 것을 함께 본다. */
    @Query("""
            select d
              from DufExamDate d
             where d.year = :year
               and (d.academy is null or d.academy.id = :academyId)
               and d.deleted = false
             order by d.examDate
            """)
    List<DufExamDate> findForAcademy(@Param("academyId") Long academyId,
                                     @Param("year") short year);
}
