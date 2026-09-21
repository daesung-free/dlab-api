package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.ExamUniversityChoice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExamUniversityChoiceRepository extends JpaRepository<ExamUniversityChoice, Long> {

    @Query("""
            SELECT c FROM ExamUniversityChoice c
            WHERE c.enrollment.id = :enrollmentId AND c.deleted = false
            ORDER BY c.examMaster.id, c.choiceRank
            """)
    List<ExamUniversityChoice> findByEnrollmentId(Long enrollmentId);

    /**
     * 그 회차의 지망대학을 지운다. 같은 회차를 다시 올리면 교체된다 — 점수와 같은 규칙이다.
     *
     * <p>soft delete 다. 이전 판정이 어땠는지가 남아야 "왜 바뀌었나" 에 답할 수 있다.
     */
    @Modifying
    @Query("""
            UPDATE ExamUniversityChoice c SET c.deleted = true
            WHERE c.enrollment.id = :enrollmentId AND c.examMaster.id = :examMasterId
              AND c.deleted = false
            """)
    int softDeleteOf(Long enrollmentId, Long examMasterId);
}
