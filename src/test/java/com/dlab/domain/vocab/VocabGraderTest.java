package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.vocab.service.MeaningSplitter;
import com.dlab.domain.vocab.service.VocabGrader;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 주관식·객관식 채점 (v1 4.2 · v2 5.2). */
class VocabGraderTest {

    private List<String> candidatesOf(String raw) {
        return MeaningSplitter.split(raw);
    }

    @Test
    @DisplayName("★★ 「금융의」라고 써도 정답이다 — 원고가 「재정의, 금융의」다")
    void acceptsAnySplitCandidate() {
        var candidates = candidatesOf("재정의, 금융의");

        assertThat(VocabGrader.isCorrect("금융의", candidates)).isTrue();
        assertThat(VocabGrader.isCorrect("재정의", candidates)).isTrue();
    }

    @Test
    @DisplayName("★ 앞뒤 공백·끝 문장부호를 다듬어 비교한다")
    void normalizesBothSides() {
        var candidates = candidatesOf("경제");

        assertThat(VocabGrader.isCorrect("  경제.  ", candidates)).isTrue();
    }

    @Test
    @DisplayName("★★ 빈 답은 정답이 아니다 — 후보가 비어 있어도 그렇다")
    void blankIsNeverCorrect() {
        assertThat(VocabGrader.isCorrect("", candidatesOf("경제"))).isFalse();
        assertThat(VocabGrader.isCorrect(null, candidatesOf("경제"))).isFalse();
        // 뜻이 없는 단어(원고 오류)의 문항은 아무도 맞힐 수 없는 것이 맞다
        assertThat(VocabGrader.isCorrect("", List.of())).isFalse();
        assertThat(VocabGrader.isCorrect("무엇", List.of())).isFalse();
    }

    @Test
    @DisplayName("★ 괄호를 뺀 답도 맞는다 — 「(온도, 각도의) 도」에 「도」")
    void acceptsParenStrippedAnswer() {
        var candidates = candidatesOf("정도, (온도, 각도의) 도");

        assertThat(VocabGrader.isCorrect("도", candidates)).isTrue();
        assertThat(VocabGrader.isCorrect("정도", candidates)).isTrue();
    }

    @Test
    @DisplayName("★ 대괄호 양쪽 다 맞는다 — 「실시[수행]하다」")
    void acceptsBracketAlternatives() {
        var candidates = candidatesOf("실시[수행]하다");

        assertThat(VocabGrader.isCorrect("실시하다", candidates)).isTrue();
        assertThat(VocabGrader.isCorrect("수행하다", candidates)).isTrue();
    }

    @Test
    @DisplayName("★★ 관리자 인정 표현도 정답이다 — 규칙으로 안 걸리는 표현을 넓히는 자리다")
    void acceptsManualCandidate() {
        var candidates = new java.util.ArrayList<>(candidatesOf("재정의, 금융의"));
        candidates.add("돈에 관한");   // MANUAL

        assertThat(VocabGrader.isCorrect("돈에 관한", candidates)).isTrue();
    }

    @Test
    @DisplayName("⚠️ 조사·어순 차이는 허용하지 않는다 — 넓히는 것은 사람이 판단한다")
    void doesNotGuessParticleDifferences() {
        var candidates = candidatesOf("재정의");

        // 「재정」만 쓰면 틀린다. 「조사를 떼고 비교」를 넣으면 「도」와 「도시」가 같아진다
        assertThat(VocabGrader.isCorrect("재정", candidates)).isFalse();
    }

    @Test
    @DisplayName("★★ 객관식은 인덱스가 아니라 고른 문구로 채점한다 — 순서를 섞어 내린다")
    void gradesChoiceByText() {
        assertThat(VocabGrader.isCorrectChoice("재정의", "재정의")).isTrue();
        assertThat(VocabGrader.isCorrectChoice(" 재정의. ", "재정의")).isTrue();
        assertThat(VocabGrader.isCorrectChoice("용감한", "재정의")).isFalse();
    }
}
