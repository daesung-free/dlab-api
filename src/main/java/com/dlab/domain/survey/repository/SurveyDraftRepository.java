package com.dlab.domain.survey.repository;

import com.dlab.domain.survey.entity.SurveyDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SurveyDraftRepository extends JpaRepository<SurveyDraft, Long> {

    @Query("""
            SELECT d FROM SurveyDraft d
            WHERE d.survey.id = :surveyId AND d.enrollment.id = :enrollmentId
              AND d.deleted = false
            """)
    Optional<SurveyDraft> findMine(Long surveyId, Long enrollmentId);
}
