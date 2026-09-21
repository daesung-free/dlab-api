package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.StudentItemResponse;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface StudentItemResponseRepository extends JpaRepository<StudentItemResponse, Long> {

    @Query("""
            SELECT r FROM StudentItemResponse r
            WHERE r.enrollment.id = :enrollmentId AND r.examMaster.id = :examMasterId
              AND r.deleted = false
            ORDER BY r.subjectKey
            """)
    List<StudentItemResponse> findOf(Long enrollmentId, Long examMasterId);

    /** 그 학생·회차의 정오를 내린다 — 다시 올리면 교체된다. */
    @Modifying
    @Query("""
            UPDATE StudentItemResponse r SET r.deleted = true
            WHERE r.enrollment.id = :enrollmentId AND r.examMaster.id = :examMasterId
              AND r.deleted = false
            """)
    int softDeleteOf(Long enrollmentId, Long examMasterId);
}
