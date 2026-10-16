package com.dlab.api.survey;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.service.SurveyService;
import com.dlab.domain.survey.service.SurveyService.QuestionCommand;
import com.dlab.domain.survey.service.SurveyService.QuestionSetting;
import com.dlab.domain.survey.service.SurveyService.SurveyCommand;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 문항 <b>수정 왕복</b> — 읽어서 그대로 되돌려 보내면 아무것도 바뀌지 않아야 한다.
 *
 * <h2>★★ 왜 이것만은 테스트로 못박아야 하는가</h2>
 * 수정 화면은 「읽어서 → 고쳐서 → 교체」로 돈다. 그런데 읽는 쪽에 설정이 빠지거나
 * 쓰는 쪽이 설정을 버리면 <b>제목 한 글자만 고쳐도 필수 여부·숫자 범위·조건부 노출이
 * 조용히 사라진다.</b> 실제로 둘 다 그랬다 —
 *
 * <ul>
 *   <li>집계({@code /results})에는 {@code required}·{@code minValue}·{@code maxValue}·
 *       조건부·합산이 <b>없었고</b>, 그게 문항을 읽을 수 있는 유일한 경로였다</li>
 *   <li>{@code replaceQuestions} 는 조건부·합산을 <b>적용하지 않아</b> 요청에 실어 보내도
 *       버려졌다 — 가채점의 「응시/미응시」 조건이 풀려 숨어야 할 문항이 전부 보였다</li>
 * </ul>
 *
 * <p><b>둘 중 하나만 고치면 왕복은 여전히 깨진다.</b> 그래서 한 쪽씩이 아니라
 * 왕복 자체를 고정한다. 그리고 <b>오류가 나지 않는 종류</b>라 테스트 말고는 드러날 곳이 없다.
 */
@SpringBootTest
@Transactional
class SurveyQuestionRoundTripTest {

    @Autowired SurveyService surveyService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        admin = new AuthPrincipal(1L, "branch", bundang.getId(),
                Set.of(Role.BRANCH_ADMIN), false, false);
    }

    /** 조건부 1 + 숫자 2(범위·필수) + 합산 1. 실제 가채점 설문의 모양이다. */
    private Survey createSurvey() {
        Instant now = clock.instant();
        return surveyService.create(admin, new SurveyCommand(
                SurveyType.GRADE_INPUT, SurveyScope.BRANCH, bundang.getId(), null,
                "가채점", "안내", false,
                now, now.plus(7, ChronoUnit.DAYS),
                List.of(
                        new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE, "국어 응시", true,
                                null, null, List.of("응시", "미응시")),
                        // 2번은 1번에서 "응시"(첫 선택지)를 고른 경우에만 보인다
                        new QuestionCommand(SurveyQuestionType.NUMBER, "국어 공통", true,
                                BigDecimal.ZERO, new BigDecimal("76"), null, 1, 1, null),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "국어 선택", false,
                                BigDecimal.ZERO, new BigDecimal("24"), null, 1, 1, null),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "국어 총점", false,
                                null, null, null, null, null, List.of(2, 3))),
                null));
    }

    /** 읽은 정의를 교체 요청으로 바꾼다 — 화면이 하는 일 그대로다. */
    private List<QuestionCommand> asCommands(List<QuestionSetting> settings) {
        return settings.stream()
                .map(q -> new QuestionCommand(q.type(), q.title(), q.required(),
                        q.minValue(), q.maxValue(),
                        q.options().isEmpty() ? null : q.options(),
                        q.showIfQuestionIndex(), q.showIfOptionIndex(),
                        q.sumOfIndexes().isEmpty() ? null : q.sumOfIndexes()))
                .toList();
    }

    @Test
    @DisplayName("★ 읽기에 설정이 다 들어 있다 — 필수·숫자 범위·조건부·합산")
    void settingsAreReadable() {
        Survey survey = createSurvey();
        em.flush();

        List<QuestionSetting> settings = surveyService.questionSettings(admin, survey.getId());

        assertThat(settings).hasSize(4);
        assertThat(settings.get(0).required()).isTrue();
        assertThat(settings.get(0).options()).containsExactly("응시", "미응시");

        // 집계에는 이 네 칸이 없었다 — 그래서 읽어서 교체하면 전부 풀렸다
        assertThat(settings.get(1).required()).isTrue();
        assertThat(settings.get(1).minValue()).isEqualByComparingTo("0");
        assertThat(settings.get(1).maxValue()).isEqualByComparingTo("76");
        assertThat(settings.get(1).showIfQuestionIndex()).isEqualTo(1);
        assertThat(settings.get(1).showIfOptionIndex()).isEqualTo(1);

        assertThat(settings.get(2).required()).isFalse();
        assertThat(settings.get(3).sumOfIndexes()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("★★ 읽어서 그대로 교체하면 아무것도 바뀌지 않는다 — 제목만 고쳐도 설정이 남는다")
    void roundTripKeepsEverything() {
        Survey survey = createSurvey();
        em.flush();

        List<QuestionSetting> before = surveyService.questionSettings(admin, survey.getId());

        // 화면이 하는 일 — 읽어서 제목 한 글자만 고치고 그대로 되돌려 보낸다
        List<QuestionCommand> edited = new java.util.ArrayList<>(asCommands(before));
        QuestionCommand first = edited.get(0);
        edited.set(0, new QuestionCommand(first.type(), "국어 응시 여부", first.required(),
                first.minValue(), first.maxValue(), first.options(),
                first.showIfQuestionIndex(), first.showIfOptionIndex(), first.sumOfIndexes()));

        surveyService.replaceQuestions(admin, survey.getId(), edited);
        em.flush();
        em.clear();

        List<QuestionSetting> after = surveyService.questionSettings(admin, survey.getId());

        assertThat(after).hasSize(4);
        assertThat(after.get(0).title()).isEqualTo("국어 응시 여부");
        assertThat(after.get(0).options()).containsExactly("응시", "미응시");

        // 고치지 않은 칸은 전부 그대로여야 한다
        assertThat(after.get(1).required()).isTrue();
        assertThat(after.get(1).minValue()).isEqualByComparingTo("0");
        assertThat(after.get(1).maxValue()).isEqualByComparingTo("76");
        assertThat(after.get(1).showIfQuestionIndex()).isEqualTo(1);
        assertThat(after.get(1).showIfOptionIndex()).isEqualTo(1);
        assertThat(after.get(2).maxValue()).isEqualByComparingTo("24");
        assertThat(after.get(3).sumOfIndexes()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("★★ 번호는 seq 가 아니라 순서다 — 두 번 교체해도 조건부가 엉뚱한 문항을 가리키지 않는다")
    void indexesSurviveRepeatedReplacement() {
        Survey survey = createSurvey();
        em.flush();

        // 교체는 soft delete 라 seq 가 이어진다 — 두 번 교체하면 첫 문항의 seq 가 9가 된다.
        // 그때 seq 를 번호로 내려주면 되돌려 보낼 때 없는 문항을 가리킨다
        for (int i = 0; i < 2; i++) {
            surveyService.replaceQuestions(admin, survey.getId(),
                    asCommands(surveyService.questionSettings(admin, survey.getId())));
            em.flush();
            em.clear();
        }

        List<QuestionSetting> after = surveyService.questionSettings(admin, survey.getId());

        assertThat(after).hasSize(4);
        assertThat(after.get(1).showIfQuestionIndex()).isEqualTo(1);
        assertThat(after.get(3).sumOfIndexes()).containsExactly(2, 3);
    }
}
