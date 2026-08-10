package com.dlab.domain.survey.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 답 한 칸.
 *
 * <p>유형마다 채우는 칸이 다르고 <b>셋 중 정확히 하나만</b> 채운다(DB CHECK로도 막았다).
 * 빈 답이 저장되면 집계에서 조용히 빠져 "응답 수가 왜 적지"로 나타난다.
 *
 * <p>복수 선택은 고른 개수만큼 이 행이 생긴다.
 */
@Getter
@Entity
@Table(name = "survey_answer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyAnswer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "response_id", nullable = false)
    private SurveyResponse response;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private SurveyQuestion question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id")
    private SurveyQuestionOption option;

    @Column(name = "text_value", columnDefinition = "text")
    private String textValue;

    @Column(name = "number_value", precision = 10, scale = 2)
    private BigDecimal numberValue;

    private SurveyAnswer(SurveyResponse response, SurveyQuestion question) {
        this.response = response;
        this.question = question;
    }

    static SurveyAnswer ofOption(SurveyResponse response, SurveyQuestion question,
                                 SurveyQuestionOption option) {
        SurveyAnswer answer = new SurveyAnswer(response, question);
        answer.option = option;
        return answer;
    }

    static SurveyAnswer ofText(SurveyResponse response, SurveyQuestion question, String text) {
        SurveyAnswer answer = new SurveyAnswer(response, question);
        answer.textValue = text;
        return answer;
    }

    static SurveyAnswer ofNumber(SurveyResponse response, SurveyQuestion question,
                                 BigDecimal number) {
        SurveyAnswer answer = new SurveyAnswer(response, question);
        answer.numberValue = number;
        return answer;
    }
}
