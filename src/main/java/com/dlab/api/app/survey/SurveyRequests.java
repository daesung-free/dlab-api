package com.dlab.api.app.survey;

import com.dlab.domain.survey.service.SurveyService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** 앱 설문 요청 본문. */
public final class SurveyRequests {

    private SurveyRequests() {
    }

    /**
     * 응답 제출.
     *
     * <p>문항 유형에 맞는 칸만 채운다 — 어느 칸을 볼지는 서버가 <b>문항 유형으로</b>
     * 정한다. 요청이 유형을 같이 보내면 앱이 잘못 보낸 유형으로 저장될 수 있다.
     */
    public record SurveySubmit(@NotEmpty(message = "응답이 비어 있습니다.") List<SurveyAnswerInput> answers) {

        List<SurveyService.AnswerCommand> toCommands() {
            return answers.stream()
                    .map(a -> new SurveyService.AnswerCommand(
                            a.questionId(), a.optionIds(), a.textValue(), a.numberValue()))
                    .toList();
        }
    }

    public record SurveyAnswerInput(@NotNull Long questionId,
                         List<Long> optionIds,
                         String textValue,
                         BigDecimal numberValue) {
    }
}
