package com.dlab.domain.grade.repository;

import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.user.entity.GradeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamMasterRepository extends JpaRepository<ExamMaster, Long> {

    /**
     * 그 지점 · 그 학년의 성적 입력 양식.
     *
     * <p>★ <b>입학 전 성적 양식만 본다.</b> 디랩 시험 행이 섞이면 학생 가입 화면에
     * "8월 더프" 입력 칸이 뜨고, 학생이 넣은 값이 연구소 성적과 같은 행에 들어간다.
     *
     * <p><b>지점 행이 하나라도 있으면 그것만 쓰고, 없으면 공통본을 쓴다.</b>
     * 둘을 합치면 같은 6월 시험이 두 번 나온다 — terms(약관)에서 같은 판단을 했다.
     */
    @Query("""
            SELECT e FROM ExamMaster e
            WHERE e.year = :year AND e.gradeType = :gradeType AND e.deleted = false
              AND e.purpose = com.dlab.domain.grade.entity.ExamPurpose.ADMISSION
              AND (
                    e.academy.id = :academyId
                 OR (e.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM ExamMaster o
                        WHERE o.year = :year AND o.gradeType = :gradeType
                          AND o.purpose = com.dlab.domain.grade.entity.ExamPurpose.ADMISSION
                          AND o.academy.id = :academyId AND o.deleted = false))
              )
            ORDER BY e.sortOrder, e.id
            """)
    List<ExamMaster> findForm(@Param("year") short year,
                              @Param("gradeType") GradeType gradeType,
                              @Param("academyId") Long academyId);

    /** 관리자 화면 — 공통본만. 지점본은 지점 관리자가 따로 본다. */
    @Query("""
            SELECT e FROM ExamMaster e
            WHERE e.year = :year AND e.deleted = false
              AND (:academyId IS NULL AND e.academy IS NULL
                   OR e.academy.id = :academyId)
            ORDER BY e.gradeType, e.sortOrder, e.id
            """)
    List<ExamMaster> findAllByScope(@Param("year") short year,
                                    @Param("academyId") Long academyId);

    @Query("""
            SELECT e FROM ExamMaster e
            WHERE e.year = :year AND e.gradeType = :gradeType AND e.examCode = :examCode
              AND e.purpose = :purpose
              AND e.deleted = false
              AND (:academyId IS NULL AND e.academy IS NULL OR e.academy.id = :academyId)
            """)
    List<ExamMaster> findSameSlot(@Param("year") short year,
                                  @Param("gradeType") GradeType gradeType,
                                  @Param("examCode") ExamCode examCode,
                                  @Param("purpose") com.dlab.domain.grade.entity.ExamPurpose purpose,
                                  @Param("academyId") Long academyId);

    /**
     * 같은 칸의 회차.
     *
     * <p>★ <b>시행일은 여기서 비교한다.</b> 쿼리에 {@code :examDate IS NULL} 을 넣으면
     * PostgreSQL 이 null 파라미터의 타입을 정하지 못해 깨진다. 월례고사는 같은 칸에 여러
     * 달이 있으므로 시행일까지 같아야 중복이다.
     */
    default Optional<ExamMaster> findOne(short year, GradeType gradeType, ExamCode examCode,
                                         com.dlab.domain.grade.entity.ExamPurpose purpose,
                                         java.time.LocalDate examDate, Long academyId) {
        return findSameSlot(year, gradeType, examCode, purpose, academyId).stream()
                .filter(e -> java.util.Objects.equals(e.getExamDate(), examDate))
                .findFirst();
    }
}
