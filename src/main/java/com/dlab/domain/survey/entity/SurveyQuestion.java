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

    /**
     * 조건부 문항 — 이 문항은 {@code showIfQuestion}에서 {@code showIfOption}을 골랐을 때만 보인다.
     * 가채점의 "응시/미응시" 가 이걸 쓴다. 비어 있으면 항상 보인다.
     *
     * <p><b>보이지 않는 문항은 필수여도 답하지 않아도 되고, 보낸 답은 버린다</b> — 미응시로 고친 뒤
     * 이전에 적은 점수가 그대로 저장되면 안 낸 시험 점수가 집계에 들어간다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_if_question_id")
    private SurveyQuestion showIfQuestion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_if_option_id")
    private SurveyQuestionOption showIfOption;

    /**
     * 합산 문항 — 같은 설문 문항 번호(seq) 목록. 가채점의 "공통 + 선택 = 총점" 이다.
     *
     * <p><b>값은 서버가 채운다.</b> 앱이 보낸 값을 받으면 합과 다른 총점이 저장될 수 있다.
     */
    @Column(name = "sum_of_seqs", length = 100)
    private String sumOfSeqs;

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

    /** 조건을 건다. 조건 문항은 이 문항보다 앞의 단일 선택 문항이어야 한다(서비스가 검사한다). */
    public void showIf(SurveyQuestion question, SurveyQuestionOption option) {
        this.showIfQuestion = question;
        this.showIfOption = option;
    }

    /** 합산 문항으로 만든다. */
    public void computeAsSumOf(List<Short> seqs) {
        this.sumOfSeqs = seqs.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    public boolean isComputed() {
        return sumOfSeqs != null && !sumOfSeqs.isBlank();
    }

    public List<Short> sumOfSeqList() {
        if (!isComputed()) {
            return List.of();
        }
        return java.util.Arrays.stream(sumOfSeqs.split(","))
                .map(String::trim).map(Short::valueOf).toList();
    }

    public boolean isConditional() {
        return showIfQuestion != null;
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
