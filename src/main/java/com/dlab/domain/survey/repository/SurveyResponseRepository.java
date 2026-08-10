package com.dlab.domain.survey.repository;

import com.dlab.domain.survey.entity.SurveyResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, Long> {

    /** 집계용. 문항·선택지까지 함께 끌어온다 — 응답 수만큼 쿼리가 나가면 화면이 못 뜬다. */
    @Query("""
            SELECT DISTINCT r FROM SurveyResponse r
            LEFT JOIN FETCH r.answers a
            LEFT JOIN FETCH a.question
            LEFT JOIN FETCH a.option
            WHERE r.survey.id = :surveyId
              AND r.deleted = false
            """)
    List<SurveyResponse> findBySurvey(@Param("surveyId") Long surveyId);

    /**
     * 내가 낸 응답.
     *
     * <p><b>익명 설문은 여기서 못 찾는다</b> — 응답 행에 응답자가 없다. 그게 익명의 정의라
     * 앱은 "제출 완료"까지만 표시한다.
     */
    @Query("""
            SELECT r FROM SurveyResponse r
            LEFT JOIN FETCH r.answers a
            LEFT JOIN FETCH a.question
            LEFT JOIN FETCH a.option
            WHERE r.survey.id = :surveyId
              AND r.enrollment.id = :enrollmentId
              AND r.deleted = false
            """)
    Optional<SurveyResponse> findMine(@Param("surveyId") Long surveyId,
                                      @Param("enrollmentId") Long enrollmentId);
}
