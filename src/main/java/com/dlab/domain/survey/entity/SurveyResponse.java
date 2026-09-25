package com.dlab.domain.survey.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 사람이 낸 응답 한 벌.
 *
 * <p><b>익명 설문이면 {@code enrollment}가 비어 있다</b>({@link SurveyParticipant} 참고).
 */
@Getter
@Entity
@Table(name = "survey_response")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyResponse extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    /** 익명 설문이면 {@code null}이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private StudentEnrollment enrollment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @OneToMany(mappedBy = "response", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SurveyAnswer> answers = new ArrayList<>();

    private SurveyResponse(Survey survey, StudentEnrollment enrollment, Instant submittedAt) {
        this.survey = survey;
        this.enrollment = enrollment;
        this.submittedAt = submittedAt;
    }

    /** 익명 여부는 설문이 정한다 — 호출부가 고르게 하면 한 설문에 두 방식이 섞인다. */
    public static SurveyResponse of(Survey survey, StudentEnrollment enrollment, Instant at) {
        return new SurveyResponse(survey, survey.isAnonymous() ? null : enrollment, at);
    }

    public void addChoice(SurveyQuestion question, SurveyQuestionOption option) {
        answers.add(SurveyAnswer.ofOption(this, question, option));
    }

    public void addText(SurveyQuestion question, String text) {
        answers.add(SurveyAnswer.ofText(this, question, text));
    }

    public void addNumber(SurveyQuestion question, BigDecimal number) {
        answers.add(SurveyAnswer.ofNumber(this, question, number));
    }

    /**
     * 재제출 — 이전 답을 지우고 새로 채울 준비를 한다.
     *
     * <p>행을 새로 만들지 않는다. 응답이 두 벌이 되면 집계에 한 사람이 두 번 들어간다.
     */
    public void resubmit(Instant at) {
        answers.forEach(SurveyAnswer::markDeleted);
        this.submittedAt = at;
    }

    public List<SurveyAnswer> activeAnswers() {
        return answers.stream().filter(a -> !a.isDeleted()).toList();
    }
}
