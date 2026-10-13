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
    public record AdminSurveyCreate(@NotNull SurveyType surveyType,
                         @NotNull SurveyScope scope,
                         Long academyId,
                         Long classId,
                         @NotBlank @Size(max = 200) String title,
                         String description,
                         Boolean anonymous,
                         @NotNull Instant opensAt,
                         @NotNull Instant closesAt,
                         @NotEmpty(message = "문항이 비어 있습니다.") List<AdminSurveyQuestion> questions,
                         Boolean allowEdit) {

        /** 생략 가능 — 없으면 false. primitive 로 두면 생략만으로 역직렬화가 깨진다. */
        public boolean anonymousOrFalse() {
            return anonymous == null ? false : anonymous;
        }

        SurveyService.SurveyCommand toCommand() {
            return new SurveyService.SurveyCommand(
                    surveyType, scope, academyId, classId, title, description, anonymous,
                    opensAt, closesAt,
                    questions.stream()
                            .map(q -> new SurveyService.QuestionCommand(
                                    q.type(), q.title(), q.required(),
                                    q.minValue(), q.maxValue(), q.options(),
                                    q.showIfQuestionIndex(), q.showIfOptionIndex(),
                                    q.sumOfIndexes()))
                            .toList(),
                    allowEdit);
        }
    }

    /**
     * 문항.
     *
     * <p>순서는 보내지 않는다 — <b>배열 순서가 곧 순서</b>다. 번호를 받으면 빠진 번호나
     * 중복이 그대로 들어와 유니크 제약에 걸린다.
     */
    /**
     * @param showIfQuestionIndex 조건부 문항 — 이 문항은 <b>앞쪽 단일 선택 문항</b>(1부터 센 순서)에서
     *                            {@code showIfOptionIndex} 번째 선택지를 골랐을 때만 보인다.
     *                            가채점의 "응시/미응시" 가 이걸 쓴다. 숨은 문항은 필수여도 묻지 않고 보낸 답은 버린다
     * @param sumOfIndexes        합산 문항 — 더할 숫자 문항 순서(1부터). 값은 서버가 채운다("공통 + 선택 = 총점")
     */
    public record AdminSurveyQuestion(@NotNull SurveyQuestionType type,
                           @NotBlank @Size(max = 300) String title,
                           boolean required,
                           BigDecimal minValue,
                           BigDecimal maxValue,
                           List<String> options,
                           Integer showIfQuestionIndex,
                           Integer showIfOptionIndex,
                           List<Integer> sumOfIndexes) {
    }

    /**
     * 문항 교체 (장학 동의서 — 2026-10-13 회신 5-1).
     *
     * <p>★ <b>제출이 하나라도 있으면 거절된다.</b> 고치면 이미 동의한 학생의 동의 내용이
     * 소급해서 바뀐다 — 그때는 복제해서 새로 만든다.
     */
    public record Questions(@NotEmpty List<AdminSurveyQuestion> questions) {

        public List<SurveyService.QuestionCommand> toCommands() {
            return questions.stream()
                    .map(q -> new SurveyService.QuestionCommand(
                            q.type(), q.title(), q.required(),
                            q.minValue(), q.maxValue(), q.options(),
                            q.showIfQuestionIndex(), q.showIfOptionIndex(),
                            q.sumOfIndexes()))
                    .toList();
        }
    }

    /** 기간·안내문 수정. 범위·대상·문항은 바꿀 수 없다. */
    public record AdminSurveyUpdate(@NotBlank @Size(max = 200) String title,
                         String description,
                         @NotNull Instant opensAt,
                         @NotNull Instant closesAt) {
    }
}
