package com.dlab.domain.vocab.service;

import com.dlab.domain.vocab.entity.VocabPhrase;
import com.dlab.domain.vocab.entity.VocabWord;

/**
 * 출제 단위 — 표제어(Part 1)이거나 구동사·숙어·혼동어휘(Part 3)다.
 *
 * <h2>★ 왜 테이블을 합치지 않고 이 타입을 두는가 (업체 확정 2026-10-22)</h2>
 * 업체가 <b>Part 3 을 시험 범위에 넣는다</b>고 확정했다(유닛당 6문항 추가). 그런데
 * Part 3 를 {@code vocab_word} 에 끼워 넣으면 <b>번호 체계가 깨진다</b> —
 * {@code seq_no} 가 {@code 0001}~{@code 2000} 으로 이어지고 원고 재업로드가 그 번호로
 * 덮어쓰는 구조다. 그래서 테이블은 둘로 두고, <b>출제·채점만 같은 자리에서</b> 본다.
 *
 * <h2>⚠️ Part 2(예문 빈칸)는 여기 없다</h2>
 * 업체가 <b>넣지 않는다</b>고 확정했다. {@code vocab_cloze} 는 적재만 유지한다 —
 * 범위에 들어오면 150행을 다시 넣지 않아도 되게.
 *
 * @param word   Part 1 이면 채워진다
 * @param phrase Part 3 이면 채워진다. ★ 둘 중 <b>정확히 하나</b>다
 */
public record ExamItem(VocabWord word, VocabPhrase phrase) {

    /** 문항이 속한 Part. 화면·통계가 이 값으로 가른다 */
    public enum Part {
        /** 표제어 2,000 */
        WORD,
        /** 구동사·숙어·혼동어휘 300 */
        PHRASE
    }

    public static ExamItem of(VocabWord word) {
        return new ExamItem(word, null);
    }

    public static ExamItem of(VocabPhrase phrase) {
        return new ExamItem(null, phrase);
    }

    public Part part() {
        return phrase != null ? Part.PHRASE : Part.WORD;
    }

    public boolean isPhrase() {
        return phrase != null;
    }

    public Short unitNo() {
        return phrase != null ? phrase.getUnitNo() : word.getUnitNo();
    }

    /** 화면에 내는 문제 — 표제어 또는 구동사·숙어 표현 */
    public String prompt() {
        return phrase != null ? phrase.getExpression() : word.getWord();
    }

    /** 원고의 뜻 한 칸. ★ 이 값으로 바로 채점하지 말 것 — 정답 후보로 쪼개야 한다 */
    public String meaningRaw() {
        return phrase != null ? phrase.getMeaning() : word.getMeaningRaw();
    }

    public Long wordId() {
        return word == null ? null : word.getId();
    }

    public Long phraseId() {
        return phrase == null ? null : phrase.getId();
    }
}
