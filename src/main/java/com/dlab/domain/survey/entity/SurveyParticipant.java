package com.dlab.domain.survey.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 참여 사실.
 *
 * <p><b>중복 제출 방지 전용이고, 응답 본문과 분리한 이유가 익명 설문이다.</b>
 * 익명인데 응답 행에 응답자를 달아두면 화면에서만 이름이 안 보일 뿐 DB에는
 * 누가 뭘 냈는지 그대로 남는다 — 그건 익명이 아니다.
 *
 * <p>실명 설문에서도 같은 경로를 쓴다. 익명일 때만 다른 방식으로 중복을 막으면
 * 그 경로에만 있는 버그가 생긴다.
 */
@Getter
@Entity
@Table(name = "survey_participant")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SurveyParticipant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /** 재제출 시각으로 갱신한다. */
    public void resubmitted(Instant at) {
        this.submittedAt = at;
    }

    public SurveyParticipant(Survey survey, StudentEnrollment enrollment, Instant submittedAt) {
        this.survey = survey;
        this.enrollment = enrollment;
        this.submittedAt = submittedAt;
    }
}
