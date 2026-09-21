package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.ExamItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExamItemRepository extends JpaRepository<ExamItem, Long> {

    @Query("""
            SELECT i FROM ExamItem i
            WHERE i.examMaster.id = :examMasterId AND i.deleted = false
            ORDER BY i.subjectKey, i.questionNo
            """)
    List<ExamItem> findByExamMasterId(Long examMasterId);

    /** 회차 문항 전체를 내린다 — 다시 올리면 통째로 교체한다. */
    @Modifying
    @Query("""
            UPDATE ExamItem i SET i.deleted = true
            WHERE i.examMaster.id = :examMasterId AND i.deleted = false
            """)
    int softDeleteByExam(Long examMasterId);
}
