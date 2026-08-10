package com.dlab.domain.survey.repository;

import com.dlab.domain.survey.entity.SurveyParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyParticipantRepository extends JpaRepository<SurveyParticipant, Long> {

    /**
     * 이미 냈는가.
     *
     * <p>익명 설문에서도 이걸로 막는다 — 응답 행에는 응답자가 없어서
     * 응답 쪽으로는 중복을 판별할 수 없다.
     */
    @Query("""
            SELECT COUNT(p) > 0 FROM SurveyParticipant p
            WHERE p.survey.id = :surveyId
              AND p.enrollment.id = :enrollmentId
              AND p.deleted = false
            """)
    boolean hasSubmitted(@Param("surveyId") Long surveyId,
                         @Param("enrollmentId") Long enrollmentId);

    /** 제출자 목록. 미제출자 독려(F-4.6)에 쓴다 — 익명이어도 "누가 냈는지"는 알 수 있다. */
    @Query("""
            SELECT p FROM SurveyParticipant p
            JOIN FETCH p.enrollment e
            JOIN FETCH e.student
            WHERE p.survey.id = :surveyId
              AND p.deleted = false
            ORDER BY p.submittedAt ASC
            """)
    List<SurveyParticipant> findBySurvey(@Param("surveyId") Long surveyId);
}
