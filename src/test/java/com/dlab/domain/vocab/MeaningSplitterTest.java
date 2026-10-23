package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.vocab.service.MeaningSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 단어 뜻 → 정답 후보 (D:VOCA v2 5.2).
 *
 * <p>예시는 전부 <b>원고 2,000개에서 실제로 나온 값</b>이다.
 */
class MeaningSplitterTest {

    @Test
    @DisplayName("★ 쉼표로 나눈다 — 「금융의」라고 써도 정답이어야 한다")
    void splitsByComma() {
        // ★ 맨 뒤의 「재정의, 금융의」는 원고 한 줄을 그대로 쓴 답을 위한 후보다
        assertThat(MeaningSplitter.split("재정의, 금융의"))
                .containsExactly("재정의", "금융의", "재정의, 금융의");
    }

    @Test
    @DisplayName("★ 세미콜론은 품사 경계다 — 둘 다 나눈다")
    void splitsBySemicolonThenComma() {
        assertThat(MeaningSplitter.split("기업, 회사; 확고한, 단단한"))
                .startsWith("기업", "회사", "확고한", "단단한")
                .as("★ 원고를 그대로 쓴 답도 정답이다 — 맨 뒤에 들어간다")
                .endsWith("기업, 회사; 확고한, 단단한");
    }

    @Test
    @DisplayName("★★ 괄호 안의 쉼표를 보호한다 — 보호 안 하면 「(온도」로 쪼개진다")
    void protectsCommaInsideParentheses() {
        // 이것이 규칙의 시험대다. 괄호 보호를 빼면 「(온도」 / 「각도의) 도」가 되고
        // 학생이 「도」라고 써도 오답이 된다
        assertThat(MeaningSplitter.split("정도, (온도, 각도의) 도"))
                .startsWith("정도", "(온도, 각도의) 도", "도")
                .doesNotContain("(온도", "각도의) 도");
    }

    @Test
    @DisplayName("★ 대괄호는 바꿔 쓸 수 있는 말 — 양쪽 다 후보다")
    void expandsBrackets() {
        assertThat(MeaningSplitter.split("실시[수행]하다; 행위, 품행"))
                .contains("실시하다", "수행하다", "행위", "품행");
    }

    @Test
    @DisplayName("★ 물결은 지운다 — 학생이 ~를 쓰지 않는다")
    void removesTilde() {
        assertThat(MeaningSplitter.split("~할 엄두를 내다, 감히 ~하다; 모험, 도전"))
                .startsWith("할 엄두를 내다", "감히 하다", "모험", "도전")
                .allSatisfy(s -> assertThat(s).doesNotContain("~"));
    }

    @Test
    @DisplayName("뜻이 하나면 그대로 한 개다")
    void singleMeaning() {
        // 쪼갤 것이 없으면 「전체」 후보가 같은 값이라 한 개로 합쳐진다
        assertThat(MeaningSplitter.split("경제")).containsExactly("경제");
    }

    @Test
    @DisplayName("빈 칸은 빈 목록 — 원고 오류 행이 있다(allow 의 해석 누락)")
    void blankIsEmpty() {
        assertThat(MeaningSplitter.split(null)).isEmpty();
        assertThat(MeaningSplitter.split("   ")).isEmpty();
    }

    @Test
    @DisplayName("★★ 학생 답은 쪼개지 않는다 — 쪼개면 여러 개 써서 맞히게 된다")
    void studentAnswerIsNotSplit() {
        // 「재정의, 금융의」를 그대로 둔다. 쪼개서 하나만 맞으면 정답으로 하면
        // 아무거나 나열해서 맞히는 것이 된다
        assertThat(MeaningSplitter.normalize("  재정의,  금융의. "))
                .isEqualTo("재정의, 금융의");
    }

    @Test
    @DisplayName("★ 정답 후보와 학생 답이 같은 규칙으로 다듬어진다")
    void sameCleaningOnBothSides() {
        var candidates = MeaningSplitter.split("재정의, 금융의");
        // 학생이 끝에 마침표를 찍거나 공백을 더 넣어도 맞아야 한다
        assertThat(candidates).contains(MeaningSplitter.normalize(" 금융의. "));
        assertThat(candidates).contains(MeaningSplitter.normalize("금융의"));
    }

    @Test
    @DisplayName("중첩 대괄호도 푼다")
    void nestedBrackets() {
        assertThat(MeaningSplitter.split("가[나]다[라]마"))
                .isNotEmpty();
    }
}
