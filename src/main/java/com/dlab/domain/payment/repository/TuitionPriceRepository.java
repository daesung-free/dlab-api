package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.user.entity.GradeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TuitionPriceRepository extends JpaRepository<TuitionPrice, Long> {

    /**
     * 그 지점에 적용되는 가격 한 건.
     *
     * <p><b>지점 행이 있으면 그것만, 없으면 공통본을 쓴다.</b> 목동·분당 재학생처럼
     * 몇 개만 다르므로, 예외만 지점 행으로 두고 나머지는 공통에 맡긴다.
     * 둘을 합치면 같은 상품이 두 번 나온다 — terms·exam_master와 같은 처리다.
     */
    @Query("""
            SELECT p FROM TuitionPrice p
            WHERE p.year = :year AND p.gradeType = :gradeType AND p.seatType = :seatType
              AND p.deleted = false
              AND (
                    p.academy.id = :academyId
                 OR (p.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM TuitionPrice o
                        WHERE o.year = :year AND o.gradeType = :gradeType
                          AND o.seatType = :seatType
                          AND o.academy.id = :academyId AND o.deleted = false))
              )
            """)
    Optional<TuitionPrice> findApplicable(@Param("year") short year,
                                          @Param("gradeType") GradeType gradeType,
                                          @Param("seatType") SeatType seatType,
                                          @Param("academyId") Long academyId);

    /** 관리자 화면 — 공통본 또는 그 지점 행만. 조회 축이 달라 위와 합치지 않는다. */
    @Query("""
            SELECT p FROM TuitionPrice p
            WHERE p.year = :year AND p.deleted = false
              AND (:academyId IS NULL AND p.academy IS NULL OR p.academy.id = :academyId)
            ORDER BY p.seatType, p.gradeType
            """)
    List<TuitionPrice> findAllByScope(@Param("year") short year,
                                      @Param("academyId") Long academyId);
}
