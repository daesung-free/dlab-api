package com.dlab.domain.survey.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 설문 문항. 선택지는 행으로 둔다 — 선택지별 응답 수 집계가 관리자 화면의 본체다. */
@Getter
@Entity
@Table(name = "survey_question")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyQuestion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @Column(nullable = false)
    private short seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 20)
    private SurveyQuestionType questionType;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false)
    private boolean required;

    /** {@link SurveyQuestionType#NUMBER} 범위. {@code null}이면 제한 없다. */
    @Column(name = "min_value", precision = 10, scale = 2)
    private BigDecimal minValue;

    @Column(name = "max_value", precision = 10, scale = 2)
    private BigDecimal maxValue;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<SurveyQuestionOption> options = new ArrayList<>();

    SurveyQuestion(Survey survey, short seq, SurveyQuestionType questionType, String title,
                   boolean required, BigDecimal minValue, BigDecimal maxValue) {
        this.survey = survey;
        this.seq = seq;
        this.questionType = questionType;
        this.title = title;
        this.required = required;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public SurveyQuestionOption addOption(String label) {
        SurveyQuestionOption option =
                new SurveyQuestionOption(this, (short) (options.size() + 1), label);
        options.add(option);
        return option;
    }

    public List<SurveyQuestionOption> activeOptions() {
        return options.stream()
                .filter(o -> !o.isDeleted())
                .sorted(Comparator.comparing(SurveyQuestionOption::getSeq))
                .toList();
    }

    /** 이 문항의 선택지인가. 남의 문항 선택지를 답으로 보내는 걸 막는다. */
    public boolean owns(SurveyQuestionOption option) {
        return option.getQuestion().getId().equals(id);
    }

    /** 범위 안인가. 범위를 안 걸어두면 가채점에 세 자리 점수가 들어와 평균이 망가진다. */
    public boolean inRange(BigDecimal value) {
        if (minValue != null && value.compareTo(minValue) < 0) {
            return false;
        }
        return maxValue == null || value.compareTo(maxValue) <= 0;
    }
}
