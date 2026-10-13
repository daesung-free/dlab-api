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

    /**
     * 제출 시각. <b>{@code null} 이면 미제출</b> — 대상자로만 등록된 상태다.
     *
     * <p>★ 전에는 NOT NULL 이었다. 제출하는 순간 행이 생기는 구조라
     * <b>「누가 아직 안 냈나」를 알 수 없었다.</b>
     */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * 앱에서 열어 본 시각.
     *
     * <p>★ <b>「발송했는데 안 봤다」와 「보고도 안 냈다」는 다른 사건이다</b> —
     * 전자는 알림을 다시 보내야 하고, 후자는 사람이 챙겨야 한다.
     */
    @Column(name = "viewed_at")
    private Instant viewedAt;

    /**
     * 동의 체크 시각.
     *
     * <p>제출과 나누는 이유는 <b>동의만 하고 제출을 안 누른 경우</b>가 실제로 있어서다.
     */
    @Column(name = "agreed_at")
    private Instant agreedAt;

    /** 재제출 시각으로 갱신한다. */
    public void resubmitted(Instant at) {
        this.submittedAt = at;
    }

    /** 열람 기록. <b>처음 열었을 때만 찍는다</b> — 다시 열어도 첫 열람 시각을 남긴다. */
    public void viewed(Instant at) {
        if (viewedAt == null) {
            viewedAt = at;
        }
    }

    /** 동의 기록. 철회가 없으므로 한 번만 찍는다. */
    public void agreed(Instant at) {
        if (agreedAt == null) {
            agreedAt = at;
        }
    }

    /**
     * 진행 상태 — 화면이 「발송 / 열람 / 동의 / 제출」을 이 값으로 그린다.
     *
     * <p>★ <b>뒤에서부터 본다.</b> 제출했으면 열람·동의도 한 것이므로, 앞에서부터 보면
     * 「열람」에서 멈춰 가장 진행된 상태를 못 알려준다.
     */
    public ConsentState state() {
        if (submittedAt != null) {
            return ConsentState.SUBMITTED;
        }
        if (agreedAt != null) {
            return ConsentState.AGREED;
        }
        if (viewedAt != null) {
            return ConsentState.VIEWED;
        }
        return ConsentState.SENT;
    }

    /** 대상자로 등록만 한다(미제출). 장학 동의서가 이 경로를 쓴다. */
    public SurveyParticipant(Survey survey, StudentEnrollment enrollment) {
        this.survey = survey;
        this.enrollment = enrollment;
    }

    public SurveyParticipant(Survey survey, StudentEnrollment enrollment, Instant submittedAt) {
        this.survey = survey;
        this.enrollment = enrollment;
        this.submittedAt = submittedAt;
    }

    /** 대상자 진행 상태. 화면이 네 칸으로 그린다. */
    public enum ConsentState {
        /** 발송했다(행이 있다) */
        SENT,
        /** 앱에서 열었다 */
        VIEWED,
        /** 동의를 체크했다 */
        AGREED,
        /** 제출했다 */
        SUBMITTED
    }
}
