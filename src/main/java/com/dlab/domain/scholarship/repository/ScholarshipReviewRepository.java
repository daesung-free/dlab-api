package com.dlab.domain.scholarship.repository;

import com.dlab.domain.scholarship.entity.CancelRuleType;
import com.dlab.domain.scholarship.entity.ReviewStatus;
import com.dlab.domain.scholarship.entity.ScholarshipReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScholarshipReviewRepository extends JpaRepository<ScholarshipReview, Long> {

    /**
     * 이미 올라온 검토 대상인가.
     *
     * <p>판정 배치가 여러 번 돌아도 <b>같은 건이 중복으로 쌓이면</b> 담당자가
     * 같은 학생을 반복해서 본다. 부분 유니크가 최후 방어선이고 여기서 먼저 거른다.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM ScholarshipReview r
            WHERE r.enrollment.id = :enrollmentId AND r.year = :year
              AND r.ruleType = :ruleType AND r.status = com.dlab.domain.scholarship.entity.ReviewStatus.PENDING
              AND r.deleted = false
            """)
    boolean existsPending(@Param("enrollmentId") Long enrollmentId,
                          @Param("year") short year,
                          @Param("ruleType") CancelRuleType ruleType);

    @Query("""
            SELECT r FROM ScholarshipReview r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId AND r.year = :year
              AND (:status IS NULL OR r.status = :status)
              AND r.deleted = false
            ORDER BY r.status, r.id DESC
            """)
    List<ScholarshipReview> findByScope(@Param("academyId") Long academyId,
                                        @Param("year") short year,
                                        @Param("status") ReviewStatus status);
}
