package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.ExamSubjectPreset;
import com.dlab.domain.user.entity.GradeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamSubjectPresetRepository extends JpaRepository<ExamSubjectPreset, Long> {

    /**
     * 이 범위에 <b>직접</b> 등록된 행. 공통본으로 떨어지지 않는다 — 수정·롤오버가 쓴다.
     *
     * @param academyId {@code null}이면 공통본
     */
    @Query("""
            SELECT p FROM ExamSubjectPreset p
            WHERE p.year = :year AND p.deleted = false
              AND (:academyId IS NULL AND p.academy IS NULL OR p.academy.id = :academyId)
            ORDER BY p.gradeType, p.sortOrder, p.id
            """)
    List<ExamSubjectPreset> findOwn(@Param("year") short year,
                                    @Param("academyId") Long academyId);

    /**
     * 그 지점 · 그 학년에 실제로 쓰일 구성. <b>지점 행이 하나라도 있으면 그것만</b>, 없으면
     * 공통본 — 합치면 같은 과목이 두 번 나온다(exam_master 와 같은 규칙).
     */
    @Query("""
            SELECT p FROM ExamSubjectPreset p
            WHERE p.year = :year AND p.gradeType = :gradeType AND p.deleted = false
              AND (
                    p.academy.id = :academyId
                 OR (p.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM ExamSubjectPreset o
                        WHERE o.year = :year AND o.gradeType = :gradeType
                          AND o.academy.id = :academyId AND o.deleted = false))
              )
            ORDER BY p.sortOrder, p.id
            """)
    List<ExamSubjectPreset> findEffective(@Param("year") short year,
                                          @Param("gradeType") GradeType gradeType,
                                          @Param("academyId") Long academyId);
}
