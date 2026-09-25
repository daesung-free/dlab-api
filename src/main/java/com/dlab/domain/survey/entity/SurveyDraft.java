package com.dlab.domain.survey.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 설문 임시저장.
 *
 * <p>가채점은 9페이지라 중간에 나가면 처음부터였다. 답을 <b>검증 없이 그대로</b> 둔다 —
 * 중간 저장에서 필수 누락으로 막으면 저장이 의미가 없다. 검증은 제출할 때 한다.
 *
 * <p><b>익명 설문은 받지 않는다</b> — 응답자와 답이 한 행에 묶여 익명이 깨진다.
 */
@Getter
@Entity
@Table(name = "survey_draft")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyDraft extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /** 답 목록 JSON. 제출 요청과 같은 모양이라 앱이 그대로 되살린다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers_json", columnDefinition = "jsonb", nullable = false)
    private String answersJson;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    public SurveyDraft(Survey survey, StudentEnrollment enrollment, String answersJson,
                       Instant savedAt) {
        this.survey = survey;
        this.enrollment = enrollment;
        this.answersJson = answersJson;
        this.savedAt = savedAt;
    }

    public void overwrite(String answersJson, Instant savedAt) {
        this.answersJson = answersJson;
        this.savedAt = savedAt;
    }
}
