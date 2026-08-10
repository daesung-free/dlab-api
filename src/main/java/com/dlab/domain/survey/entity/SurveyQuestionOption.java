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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 선택지. */
@Getter
@Entity
@Table(name = "survey_question_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyQuestionOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private SurveyQuestion question;

    @Column(nullable = false)
    private short seq;

    @Column(nullable = false, length = 200)
    private String label;

    SurveyQuestionOption(SurveyQuestion question, short seq, String label) {
        this.question = question;
        this.seq = seq;
        this.label = label;
    }
}
