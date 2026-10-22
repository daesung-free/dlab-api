package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * D:VOCA 지점 시험 설정 (v1 5.5 · v2 6.2).
 *
 * <p>★ <b>일정은 지점마다 따로 돈다</b>(v1 5.1). 시작일과 출제 설정이 여기 모인다.
 *
 * <p>⚠️ 문항 수·보기 수·객관식 비율은 <b>업체 확인 중</b>이다(v1 Q2) — 기본값을 두고
 * 관리자 화면에서 고친다. <b>코드에 박지 않는다</b>(v2 6.2).
 */
@Entity
@Table(name = "vocab_schedule_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabScheduleConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private Short year;

    /** 1회독 1유닛이 공개되는 첫 시험일. */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "question_count", nullable = false)
    private short questionCount = 20;

    /**
     * 금요일 누적 상한.
     *
     * <p>★ 3회독 누적은 범위가 최대 20유닛 = <b>800단어</b>다. 상한 없이는 800문항이 된다.
     */
    @Column(name = "weekly_question_cap", nullable = false)
    private short weeklyQuestionCap = 40;

    @Column(name = "choice_count", nullable = false)
    private short choiceCount = 4;

    /**
     * 객관식 비율(%).
     *
     * <p>★ <b>주관식이 섞여야 뜻을 쓰게 되고</b>, 그 답이 인정 표현을 넓히는 근거가 된다
     * (v1 4.2).
     */
    @Column(name = "multiple_choice_rate", nullable = false)
    private short multipleChoiceRate = 50;

    public VocabScheduleConfig(Academy academy, Short year, LocalDate startDate) {
        this.academy = academy;
        this.year = year;
        this.startDate = startDate;
    }

    /** 비워 보낸 항목은 바꾸지 않는다. */
    public void modify(LocalDate startDate, Short questionCount, Short weeklyQuestionCap,
                       Short choiceCount, Short multipleChoiceRate) {
        if (startDate != null) {
            this.startDate = startDate;
        }
        if (questionCount != null) {
            this.questionCount = questionCount;
        }
        if (weeklyQuestionCap != null) {
            this.weeklyQuestionCap = weeklyQuestionCap;
        }
        if (choiceCount != null) {
            this.choiceCount = choiceCount;
        }
        if (multipleChoiceRate != null) {
            this.multipleChoiceRate = multipleChoiceRate;
        }
    }

    /** 그 회차의 문항 수 — 누적이면 상한을 쓴다. */
    public int questionCountFor(boolean weekly) {
        return weekly ? weeklyQuestionCap : questionCount;
    }
}
