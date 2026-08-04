package com.dlab.domain.period.repository;

import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PeriodMasterRepository extends JpaRepository<PeriodMaster, Long> {

    /** 그 요일 구분의 교시 전체(시작시각 순). 비어 있으면 그날은 시간표가 없다는 뜻이다. */
    @Query("""
            SELECT p FROM PeriodMaster p
            WHERE p.academy.id = :academyId
              AND p.year = :year
              AND p.dayType = :dayType
              AND p.deleted = false
            ORDER BY p.startTime ASC
            """)
    List<PeriodMaster> findByDayType(@Param("academyId") Long academyId,
                                     @Param("year") short year,
                                     @Param("dayType") DayType dayType);
}
