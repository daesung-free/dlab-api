package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.service.MeaningSplitter;
import com.dlab.domain.vocab.service.VocabGrader;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 업체 확정 답변 (2026-10-22 「디보카 개발 피드백 답변」).
 *
 * <h2>★★ 여기 있는 예시는 업체 회신에 적힌 단어 그대로다</h2>
 * 주관식 채점 규칙(v2 5.2)은 우리가 제안하고 업체가 <b>그대로 승인</b>했다. 규칙은
 * 글로만 남으면 다음 사람이 「정규화를 단순하게 고치자」로 되돌린다 —
 * <b>회신에 적힌 단어로 못 박아</b> 되돌리면 테스트가 깨지게 한다.
 *
 * <p>★ <b>왜 이 규칙이 값진가</b>: 뜻이 한 개뿐인 단어가 2,000개 중 <b>539개(27%)</b>다.
 * 규칙이 없으면 나머지 1,461개마다 관리자가 인정 표현을 손으로 넣어야 한다.
 */
class VocabVendorAnswerTest {

    private List<String> candidates(String raw) {
        return MeaningSplitter.split(raw);
    }

    /** ★ 회신 1번 — 주관식 채점 규칙. 예시 단어가 전부 회신 원문에 있다 */
    @Nested
    @DisplayName("1. 주관식 채점 규칙 (업체 승인)")
    class Grading {

        @Test
        @DisplayName("financial = 「재정의, 금융의」 → 쉼표로 나눈 각각이 정답")
        void comma() {
            var c = candidates("재정의, 금융의");

            // 맨 뒤 후보는 원고 한 줄을 그대로 쓴 답을 위한 것이다
            assertThat(c).startsWith("재정의", "금융의");
            assertThat(VocabGrader.isCorrect("재정의", c)).isTrue();
            assertThat(VocabGrader.isCorrect("금융의", c)).isTrue();
            // ⚠️ 원고 전체를 그대로 쓴 학생도 맞아야 한다 — 교재를 보고 베껴 쓰는 학생이 있다
            assertThat(VocabGrader.isCorrect("재정의, 금융의", c)).isTrue();
        }

        @Test
        @DisplayName("firm = 「기업, 회사; 확고한, 단단한」 → 세미콜론은 품사 경계다")
        void semicolon() {
            var c = candidates("기업, 회사; 확고한, 단단한");

            assertThat(c).startsWith("기업", "회사", "확고한", "단단한");
            for (String answer : c) {
                assertThat(VocabGrader.isCorrect(answer, c))
                        .as("「%s」", answer).isTrue();
            }
        }

        @Test
        @DisplayName("★★ degree = 「정도, (온도, 각도의) 도」 → 괄호는 「뺀 것」과 「안 뺀 것」 둘 다 정답")
        void parenthesesBothWays() {
            var c = candidates("정도, (온도, 각도의) 도");

            // ① 괄호를 빼고 쓴 학생 — 설명이라 안 쓰는 쪽이 보통이다
            assertThat(VocabGrader.isCorrect("도", c)).isTrue();
            // ② 괄호까지 그대로 쓴 학생 — ★ 한쪽만 보면 이 정상 답이 오답이 된다
            assertThat(VocabGrader.isCorrect("(온도, 각도의) 도", c)).isTrue();
            assertThat(VocabGrader.isCorrect("정도", c)).isTrue();
            // ⚠️ 괄호 안의 쉼표로 쪼개지면 「(온도」 같은 뜻 아닌 후보가 생긴다
            assertThat(c).doesNotContain("(온도", "각도의) 도", "온도", "각도의");
        }

        @Test
        @DisplayName("conduct = 「실시[수행]하다; 행위, 품행」 → 대괄호는 양쪽 다 정답")
        void brackets() {
            var c = candidates("실시[수행]하다; 행위, 품행");

            assertThat(VocabGrader.isCorrect("실시하다", c)).isTrue();
            assertThat(VocabGrader.isCorrect("수행하다", c)).isTrue();
            assertThat(VocabGrader.isCorrect("행위", c)).isTrue();
            assertThat(VocabGrader.isCorrect("품행", c)).isTrue();
            // ⚠️ 대괄호 기호를 그대로 쓴 답은 허용하지 않는다 — 학생이 「[ ]」를 입력할
            //    일이 없고, 허용하려면 기호를 지우고 비교해야 해서 다른 오답까지 붙는다
            assertThat(VocabGrader.isCorrect("실시[수행]하다", c)).isFalse();
        }

        @Test
        @DisplayName("dare = 「~할 엄두를 내다, 감히 ~하다; 모험, 도전」 → 물결은 빼고 비교")
        void tilde() {
            var c = candidates("~할 엄두를 내다, 감히 ~하다; 모험, 도전");

            assertThat(VocabGrader.isCorrect("할 엄두를 내다", c)).isTrue();
            // 학생이 물결을 쓰든 안 쓰든 같은 판정이어야 한다 — 양쪽에 같은 규칙을 쓴다
            assertThat(VocabGrader.isCorrect("~할 엄두를 내다", c)).isTrue();
            assertThat(VocabGrader.isCorrect("감히 ~하다", c)).isTrue();
            assertThat(VocabGrader.isCorrect("감히 하다", c)).isTrue();
            assertThat(VocabGrader.isCorrect("모험", c)).isTrue();
        }

        @Test
        @DisplayName("★ 앞뒤 공백·연속 공백·끝 문장부호를 정리한다")
        void whitespaceAndPunctuation() {
            var c = candidates("감히 ~하다");

            assertThat(VocabGrader.isCorrect("  감히   하다.  ", c)).isTrue();
        }

        @Test
        @DisplayName("⚠️ 아무 뜻이나 맞는 것은 아니다 — 규칙이 넓어도 틀린 답은 틀린다")
        void stillRejectsWrongAnswers() {
            var c = candidates("재정의, 금융의");

            assertThat(VocabGrader.isCorrect("용감한", c)).isFalse();
            assertThat(VocabGrader.isCorrect("재정", c)).isFalse();
            assertThat(VocabGrader.isCorrect("", c)).isFalse();
        }
    }

    /** ★ 회신 3번 — 문항 수 30. 누적 시험도 상한 30이다 */
    @Nested
    @DisplayName("3. 문항 수 30 · 4. Part 3 포함 · 5. 결과 상세 노출 (기본값)")
    class Defaults {

        @Test
        @DisplayName("★ 새 지점 설정은 30문항 — 누적(금요일)도 30이다")
        void questionCountIsThirty() {
            var config = new VocabScheduleConfig(null, (short) 2026, LocalDate.of(2026, 12, 1));

            assertThat(config.getQuestionCount()).isEqualTo((short) 30);
            assertThat(config.questionCountFor(false)).isEqualTo(30);
            // ★★ 누적은 3회독이면 최대 20유닛 800단어다 — 상한이 없으면 800문항이 된다
            assertThat(config.questionCountFor(true)).isEqualTo(30);
        }

        @Test
        @DisplayName("★ Part 3 출제와 결과 상세 노출이 기본 켜짐이다 — 설정으로 끌 수 있다")
        void phraseAndDetailAreOnByDefault() {
            var config = new VocabScheduleConfig(null, (short) 2026, LocalDate.of(2026, 12, 1));

            assertThat(config.isIncludePhrase()).isTrue();
            assertThat(config.isShowWordDetail()).isTrue();

            config.modify(null, null, null, null, null, false, false);
            assertThat(config.isIncludePhrase()).isFalse();
            assertThat(config.isShowWordDetail()).isFalse();
        }

        @Test
        @DisplayName("비워 보낸 항목은 바꾸지 않는다 — 켜고 끄는 값이라 특히 쉽게 뒤집힌다")
        void blankKeepsPreviousValue() {
            var config = new VocabScheduleConfig(null, (short) 2026, LocalDate.of(2026, 12, 1));
            config.modify(null, null, null, null, null, false, false);

            config.modify(null, (short) 10, null, null, null, null, null);

            assertThat(config.getQuestionCount()).isEqualTo((short) 10);
            assertThat(config.isIncludePhrase()).as("★ null 은 「그대로」다").isFalse();
            assertThat(config.isShowWordDetail()).isFalse();
        }
    }
}
