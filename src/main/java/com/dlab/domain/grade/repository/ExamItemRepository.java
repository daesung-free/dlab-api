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

    /**
     * 회차별 문항 수와 올린 시각을 <b>한 번에</b> 센다. 회차 목록이 회차마다 부르면 쿼리가 회차 수만큼 나간다.
     *
     * <p>문항이 없는 회차는 <b>행 자체가 안 나온다</b>. 받는 쪽에서 0으로 채운다.
     */
    @Query("""
            SELECT i.examMaster.id, COUNT(i), MAX(i.createdAt)
            FROM ExamItem i
            WHERE i.examMaster.id IN :examMasterIds AND i.deleted = false
            GROUP BY i.examMaster.id
            """)
    List<Object[]> countByExamMasterIds(java.util.Collection<Long> examMasterIds);

    /** 회차 문항 전체를 내린다 — 다시 올리면 통째로 교체한다. */
    @Modifying
    @Query("""
            UPDATE ExamItem i SET i.deleted = true
            WHERE i.examMaster.id = :examMasterId AND i.deleted = false
            """)
    int softDeleteByExam(Long examMasterId);
}
