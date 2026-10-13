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
     *
     * <h2>★★ 행 존재만 보면 안 된다</h2>
     * 장학 동의서는 <b>대상자를 미리 등록</b>하므로 제출 전에도 행이 있다. 행만 세면
     * <b>발송받은 학생이 「이미 냈다」로 판정돼 제출 자체가 막힌다.</b>
     * {@code submittedAt} 을 함께 본다.
     */
    @Query("""
            SELECT COUNT(p) > 0 FROM SurveyParticipant p
            WHERE p.survey.id = :surveyId
              AND p.enrollment.id = :enrollmentId
              AND p.submittedAt IS NOT NULL
              AND p.deleted = false
            """)
    boolean hasSubmitted(@Param("surveyId") Long surveyId,
                         @Param("enrollmentId") Long enrollmentId);

    /** 그 설문에 제출된 건수. 문항 편집을 막을지 판단한다. */
    @Query("""
            SELECT COUNT(p) FROM SurveyParticipant p
            WHERE p.survey.id = :surveyId
              AND p.submittedAt IS NOT NULL
              AND p.deleted = false
            """)
    long countSubmitted(@Param("surveyId") Long surveyId);

    /** 이미 대상자인가. 같은 학생에게 두 번 발송하지 않는다. */
    @Query("""
            SELECT COUNT(p) > 0 FROM SurveyParticipant p
            WHERE p.survey.id = :surveyId
              AND p.enrollment.id = :enrollmentId
              AND p.deleted = false
            """)
    boolean isTarget(@Param("surveyId") Long surveyId,
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

    /** 내 참여 기록. 재제출 시각을 갱신할 때 쓴다. */
    @Query("""
            SELECT p FROM SurveyParticipant p
            WHERE p.survey.id = :surveyId
              AND p.enrollment.id = :enrollmentId
              AND p.deleted = false
            """)
    java.util.Optional<SurveyParticipant> findMine(@Param("surveyId") Long surveyId,
                                                   @Param("enrollmentId") Long enrollmentId);
}
