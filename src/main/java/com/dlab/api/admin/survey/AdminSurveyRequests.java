package com.dlab.api.admin.survey;

import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.service.SurveyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 관리자 설문 요청 본문. */
public final class AdminSurveyRequests {

    private AdminSurveyRequests() {
    }

    /**
     * 개설.
     *
     * <p>{@code academyId}·{@code classId}는 범위에 맞는 것만 채운다 —
     * 어긋나면 서비스가 거부한다(DB CHECK로도 막혀 있다).
     */
    public record Create(@NotNull SurveyType surveyType,
                         @NotNull SurveyScope scope,
                         Long academyId,
                         Long classId,
                         @NotBlank @Size(max = 200) String title,
                         String description,
                         boolean anonymous,
                         @NotNull Instant opensAt,
                         @NotNull Instant closesAt,
                         @NotEmpty(message = "문항이 비어 있습니다.") List<Question> questions) {

        SurveyService.SurveyCommand toCommand() {
            return new SurveyService.SurveyCommand(
                    surveyType, scope, academyId, classId, title, description, anonymous,
                    opensAt, closesAt,
                    questions.stream()
                            .map(q -> new SurveyService.QuestionCommand(
                                    q.type(), q.title(), q.required(),
                                    q.minValue(), q.maxValue(), q.options()))
                            .toList());
        }
    }

    /**
     * 문항.
     *
     * <p>순서는 보내지 않는다 — <b>배열 순서가 곧 순서</b>다. 번호를 받으면 빠진 번호나
     * 중복이 그대로 들어와 유니크 제약에 걸린다.
     */
    public record Question(@NotNull SurveyQuestionType type,
                           @NotBlank @Size(max = 300) String title,
                           boolean required,
                           BigDecimal minValue,
                           BigDecimal maxValue,
                           List<String> options) {
    }

    /** 기간·안내문 수정. 범위·대상·문항은 바꿀 수 없다. */
    public record Update(@NotBlank @Size(max = 200) String title,
                         String description,
                         @NotNull Instant opensAt,
                         @NotNull Instant closesAt) {
    }
}
