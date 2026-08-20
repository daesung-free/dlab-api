package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.ExamSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamSubjectRepository extends JpaRepository<ExamSubject, Long> {

    /**
     * 여러 회차의 과목을 한 번에.
     *
     * <p>양식 조회는 회차 3개를 함께 내리므로 회차마다 따로 조회하면 N+1이 된다.
     */
    @Query("""
            SELECT s FROM ExamSubject s
            WHERE s.examMaster.id IN :examMasterIds AND s.deleted = false
            ORDER BY s.sortOrder, s.id
            """)
    List<ExamSubject> findByExamMasterIds(@Param("examMasterIds") List<Long> examMasterIds);
}
