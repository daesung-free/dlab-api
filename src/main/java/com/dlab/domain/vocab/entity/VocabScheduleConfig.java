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

    /** ★ 업체 확정 30 (2026-10-22). 범위의 문항 수보다 크면 그 수로 줄어든다 */
    @Column(name = "question_count", nullable = false)
    private short questionCount = 30;

    /**
     * 금요일 누적 상한.
     *
     * <p>★ 3회독 누적은 범위가 최대 20유닛 = <b>800단어</b>다. 상한 없이는 800문항이 된다.
     *
     * <p>★ <b>업체 확정으로 평일과 같은 30이다</b>(2026-10-22) — 누적이라고 더 내지 않는다.
     */
    @Column(name = "weekly_question_cap", nullable = false)
    private short weeklyQuestionCap = 30;

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

    /**
     * Part 3(구동사·숙어·혼동어휘)을 시험 범위에 넣는가.
     *
     * <p>★ <b>업체 확정으로 넣는다</b>(2026-10-22) — 표제어와 같은 방식(뜻 맞히기)으로
     * 출제하고 유닛당 6문항이 늘어난다. ⚠️ Part 2(예문 빈칸)는 <b>범위 밖</b>이라
     * 설정이 없다 — 데이터만 적재해 둔다.
     */
    @Column(name = "include_phrase", nullable = false)
    private boolean includePhrase = true;

    /**
     * 결과 화면에 발음·예문·유의어를 보여주는가.
     *
     * <p>★ <b>업체 확정으로 보여준다</b>(2026-10-22). 데이터는 늘 저장하고 <b>노출만</b>
     * 끈다 — 끄기 위해 2,000행을 다시 넣는 일이 없게.
     */
    @Column(name = "show_word_detail", nullable = false)
    private boolean showWordDetail = true;

    public VocabScheduleConfig(Academy academy, Short year, LocalDate startDate) {
        this.academy = academy;
        this.year = year;
        this.startDate = startDate;
    }

    /** 비워 보낸 항목은 바꾸지 않는다. */
    public void modify(LocalDate startDate, Short questionCount, Short weeklyQuestionCap,
                       Short choiceCount, Short multipleChoiceRate,
                       Boolean includePhrase, Boolean showWordDetail) {
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
        if (includePhrase != null) {
            this.includePhrase = includePhrase;
        }
        if (showWordDetail != null) {
            this.showWordDetail = showWordDetail;
        }
    }

    /** 그 회차의 문항 수 — 누적이면 상한을 쓴다. */
    public int questionCountFor(boolean weekly) {
        return weekly ? weeklyQuestionCap : questionCount;
    }
}
