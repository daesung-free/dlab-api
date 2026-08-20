package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.StudentGradeSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StudentGradeSubmissionRepository
        extends JpaRepository<StudentGradeSubmission, Long> {

    @Query("""
            SELECT s FROM StudentGradeSubmission s
            WHERE s.enrollment.id = :enrollmentId AND s.deleted = false
            """)
    Optional<StudentGradeSubmission> findByEnrollmentId(@Param("enrollmentId") Long enrollmentId);

    /** 관리자 목록 — 미제출자 파악용. 승인 심사에서 성적을 같이 본다. */
    @Query("""
            SELECT s FROM StudentGradeSubmission s
            WHERE s.academy.id = :academyId AND s.year = :year AND s.deleted = false
              AND s.enrollment.id IN :enrollmentIds
            """)
    List<StudentGradeSubmission> findByEnrollmentIds(@Param("academyId") Long academyId,
                                                     @Param("year") short year,
                                                     @Param("enrollmentIds") List<Long> enrollmentIds);
}
