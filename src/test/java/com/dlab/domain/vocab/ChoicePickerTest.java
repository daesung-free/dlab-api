package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.service.ChoicePicker;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 객관식 보기 추출 (v2 6.1).
 *
 * <p>★ DB 를 타지 않는다 — 규칙이 전부 메모리 연산이고, 그래야 경계를 손으로 만들 수 있다.
 */
class ChoicePickerTest {

    private static final Random FIXED = new Random(42);

    private VocabWord word(long id, String w, String pos, String meaning) {
        return word(id, w, pos, meaning, null);
    }

    private VocabWord word(long id, String w, String pos, String meaning, String synonym) {
        var vw = new VocabWord((short) 1, String.format("%04d", id), w, meaning);
        vw.overwrite((short) 1, w, null, pos, meaning, synonym,
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(vw, "id", id);
        return vw;
    }

    @Test
    @DisplayName("★ 같은 유닛·같은 품사에서 오답 3개를 뽑는다")
    void picksFromSameUnitAndPos() {
        var answer = word(1, "financial", "a", "재정의, 금융의");
        var pool = List.of(answer,
                word(2, "brave", "a", "용감한"),
                word(3, "calm", "a", "차분한"),
                word(4, "eager", "a", "열망하는"),
                word(5, "run", "v", "달리다"));

        var choices = ChoicePicker.pick(answer, pool, pool, 4, FIXED);

        assertThat(choices.texts()).hasSize(4);
        assertThat(choices.texts()).contains("재정의");
        // ★ 품사가 다른 「달리다」는 안 들어온다 — 섞이면 답이 쉽게 보인다
        assertThat(choices.texts()).doesNotContain("달리다");
        assertThat(choices.texts().get(choices.answerIndex())).isEqualTo("재정의");
    }

    @Test
    @DisplayName("★★ 같은 유닛에 같은 품사가 모자라면 다른 유닛에서 채운다 — 실측 28건이 그렇다")
    void fallsBackToOtherUnits() {
        var answer = word(1, "beneath", "p", "아래에");
        // 같은 유닛에 전치사가 정답 하나뿐이다
        var sameUnit = List.of(answer, word(2, "run", "v", "달리다"));
        var all = List.of(answer,
                word(2, "run", "v", "달리다"),
                word(10, "above", "p", "위에"),
                word(11, "within", "p", "안에"),
                word(12, "beyond", "p", "넘어"));

        var choices = ChoicePicker.pick(answer, sameUnit, all, 4, FIXED);

        assertThat(choices.texts()).hasSize(4);
        // 다른 유닛의 전치사로 채운다
        assertThat(choices.texts()).contains("위에", "안에", "넘어");
    }

    @Test
    @DisplayName("★★ 전체에도 같은 품사가 모자라면 품사를 풀고 채운다 — c·p 가 그렇다")
    void fallsBackToAnyPos() {
        var answer = word(1, "and", "c", "그리고");
        // 접속사가 교재 전체에 1개뿐이다(실측: c 는 1건)
        var all = List.of(answer,
                word(2, "run", "v", "달리다"),
                word(3, "book", "n", "책"),
                word(4, "brave", "a", "용감한"));

        var choices = ChoicePicker.pick(answer, all, all, 4, FIXED);

        // ★ 품사를 풀어서라도 4개를 채운다 — 보기가 2개인 문항을 내면 안 된다
        assertThat(choices.texts()).hasSize(4);
        assertThat(choices.texts()).contains("그리고");
    }

    @Test
    @DisplayName("★★ 품사가 빈 단어도 보기가 만들어진다 — 원고에 1건 있다(beneath)")
    void handlesMissingPos() {
        var answer = word(1, "beneath", null, "아래에");
        var all = List.of(answer,
                word(2, "run", "v", "달리다"),
                word(3, "book", "n", "책"),
                word(4, "brave", "a", "용감한"));

        var choices = ChoicePicker.pick(answer, all, all, 4, FIXED);

        assertThat(choices.texts()).hasSize(4);
    }

    @Test
    @DisplayName("★★ 유의어는 오답으로 뽑지 않는다 — 정답이 둘이 되면 그 문항은 아무것도 못 측정한다")
    void excludesSynonyms() {
        // 정답의 유의어 열에 「경제」가 있다
        var answer = word(1, "financial", "a", "재정의", "경제");
        var all = List.of(answer,
                word(2, "economic", "a", "경제"),
                word(3, "brave", "a", "용감한"),
                word(4, "calm", "a", "차분한"),
                word(5, "eager", "a", "열망하는"));

        var choices = ChoicePicker.pick(answer, all, all, 4, FIXED);

        assertThat(choices.texts()).doesNotContain("경제");
        assertThat(choices.texts()).hasSize(4);
    }

    @Test
    @DisplayName("★ 출제에서 뺀 단어는 보기로도 안 쓴다")
    void skipsInactiveWords() {
        var answer = word(1, "financial", "a", "재정의");
        var inactive = word(2, "brave", "a", "용감한");
        inactive.changeActive(false);
        var all = List.of(answer, inactive,
                word(3, "calm", "a", "차분한"),
                word(4, "eager", "a", "열망하는"));

        var choices = ChoicePicker.pick(answer, all, all, 4, FIXED);

        assertThat(choices.texts()).doesNotContain("용감한");
    }

    @Test
    @DisplayName("★ 뜻 전체가 아니라 첫 후보를 보기에 쓴다 — 품사가 다른 뜻이 한 보기에 섞인다")
    void usesFirstMeaningOnly() {
        var answer = word(1, "firm", "n", "기업, 회사; 확고한, 단단한");
        var all = List.of(answer,
                word(2, "book", "n", "책"),
                word(3, "desk", "n", "책상"),
                word(4, "chair", "n", "의자"));

        var choices = ChoicePicker.pick(answer, all, all, 4, FIXED);

        assertThat(choices.texts()).contains("기업")
                .doesNotContain("기업, 회사; 확고한, 단단한");
    }

    @Test
    @DisplayName("★ 정답 위치가 고정이 아니다 — 고정이면 학생이 눌러서 찾는다")
    void answerPositionVaries() {
        var answer = word(1, "financial", "a", "재정의");
        var all = List.of(answer,
                word(2, "brave", "a", "용감한"),
                word(3, "calm", "a", "차분한"),
                word(4, "eager", "a", "열망하는"));

        var positions = new java.util.HashSet<Integer>();
        for (int i = 0; i < 40; i++) {
            positions.add(ChoicePicker.pick(answer, all, all, 4, new Random(i)).answerIndex());
        }
        assertThat(positions).hasSizeGreaterThan(1);
    }
}
