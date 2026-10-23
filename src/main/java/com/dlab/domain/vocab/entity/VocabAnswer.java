package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
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
import jakarta.persistence.Table;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문항별 답 (v1 4.2).
 *
 * <h2>★★ 그때의 답·정오·보기를 들고 있어야 한다</h2>
 * 단어를 참조만 하면 관리자가 뜻을 고치는 순간 <b>「그 학생이 왜 틀렸는지」를 설명할 수
 * 없다.</b> 보기도 저장한다 — 다시 뽑으면 <b>오답 분석이 성립하지 않는다.</b>
 */
@Entity
@Table(name = "vocab_answer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabAnswer extends BaseEntity {

    /** 문항 유형. {@code MC}=객관식 · {@code SA}=주관식 */
    public enum Type {
        MC, SA
    }

    /** 보기 구분자. ⚠️ 뜻에 쓰이지 않는 문자여야 한다 — 쉼표·세미콜론은 뜻에 들어간다 */
    private static final String CHOICE_DELIMITER = "|";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private VocabAttempt attempt;

    /**
     * 표제어 문항(Part 1).
     *
     * <p>★ Part 3 문항이면 {@code null} 이고 {@link #phrase} 가 채워진다 —
     * <b>둘 중 정확히 하나</b>다(DB 제약 {@code ck_vocab_answer_item}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    private VocabWord word;

    /**
     * Part 3 문항 — 구동사·숙어·혼동어휘 (업체 확정 2026-10-22).
     *
     * <p>★ <b>표제어 테이블에 끼워 넣지 않는다.</b> {@code vocab_word} 는 번호
     * {@code 0001}~{@code 2000} 체계이고 재업로드가 그 번호로 덮어쓴다 — 구동사를
     * 섞으면 그 구조가 통째로 무너진다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "phrase_id")
    private VocabPhrase phrase;

    /** 문항 번호(1-based). 화면이 「3번 문항」으로 말하는 그 번호다 */
    @Column(name = "seq_no", nullable = false)
    private Short seqNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    /** ★ 객관식 보기 스냅샷. 주관식은 비어 있다 */
    @Column(length = 1000)
    private String choices;

    /** ★ 학생이 낸 답. <b>빈 답(무응답)도 남긴다</b> — 인정 표현을 넓히는 근거다 */
    @Column(length = 300)
    private String given;

    /** ★ {@code null} 이면 채점 전이다. 채점은 서버에서만 한다 */
    private Boolean correct;

    public VocabAnswer(VocabAttempt attempt, VocabWord word, short seqNo,
                       Type type, List<String> choices) {
        this(attempt, seqNo, type, choices);
        this.word = word;
    }

    /** Part 3 문항. ★ {@code word} 는 비워 둔다 — 둘 중 하나만 채운다 */
    public VocabAnswer(VocabAttempt attempt, VocabPhrase phrase, short seqNo,
                       Type type, List<String> choices) {
        this(attempt, seqNo, type, choices);
        this.phrase = phrase;
    }

    private VocabAnswer(VocabAttempt attempt, short seqNo, Type type, List<String> choices) {
        this.attempt = attempt;
        this.seqNo = seqNo;
        this.type = type;
        this.choices = choices == null || choices.isEmpty()
                ? null : String.join(CHOICE_DELIMITER, choices);
    }

    /** 문항이 Part 3 인가. */
    public boolean isPhrase() {
        return phrase != null;
    }

    /**
     * 화면에 내는 문제 — 표제어이거나 구동사·숙어다.
     *
     * <p>⚠️ 호출하면 지연 로딩이 풀린다. 목록에서 문항마다 부르지 말 것.
     */
    public String prompt() {
        return phrase != null ? phrase.getExpression() : word.getWord();
    }

    /** 정답(원고 뜻) — Part 3 는 {@code vocab_phrase.meaning} 이다. */
    public String meaningRaw() {
        return phrase != null ? phrase.getMeaning() : word.getMeaningRaw();
    }

    public List<String> choiceList() {
        return choices == null || choices.isBlank()
                ? List.of() : List.of(choices.split("\\" + CHOICE_DELIMITER, -1));
    }

    /** 채점 결과를 적는다. ★ 답은 그대로 남긴다 — 틀린 답이 인정 표현의 근거다 */
    public void grade(String given, boolean correct) {
        this.given = given;
        this.correct = correct;
    }
}
