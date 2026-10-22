package com.dlab.api.admin.vocab;

import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabNoExamDay;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.service.RoundScheduler;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 통합관리 — D:VOCA 일정 응답. */
public final class AdminVocabScheduleResponse {

    private AdminVocabScheduleResponse() {
    }

    @Schema(name = "VocabScheduleConfigView", description = "지점 시작일·출제 설정")
    public record Config(
            Long academyId,
            String academyName,
            Short year,
            LocalDate startDate,
            Short questionCount,
            Short weeklyQuestionCap,
            Short choiceCount,
            Short multipleChoiceRate,
            @Schema(description = "Part 3(구동사·숙어·혼동어휘) 출제 여부")
            boolean includePhrase,
            @Schema(description = "결과 화면 발음·예문·유의어 노출 여부")
            boolean showWordDetail) {

        public static Config from(VocabScheduleConfig c) {
            return new Config(c.getAcademy().getId(), c.getAcademy().getName(), c.getYear(),
                    c.getStartDate(), c.getQuestionCount(), c.getWeeklyQuestionCap(),
                    c.getChoiceCount(), c.getMultipleChoiceRate(),
                    c.isIncludePhrase(), c.isShowWordDetail());
        }
    }

    @Schema(name = "VocabNoExamDayView", description = "시험 없는 날")
    public record NoExamDay(Long id, LocalDate date, String reason) {

        public static NoExamDay from(VocabNoExamDay d) {
            return new NoExamDay(d.getId(), d.getExamDate(), d.getReason());
        }
    }

    /**
     * 달력 미리보기 한 칸 (v1 7장).
     *
     * <p>★ <b>저장된 회차가 아니다 — 계산 결과다</b>(v1 5.6). 관리자가 시험 없는 날을
     * 넣기 전에 결과를 보는 화면이고, 공개는 예정일이 되어야 일어난다.
     */
    @Schema(name = "VocabRoundPreview", description = "회차 달력 미리보기 — 계산 결과")
    public record Preview(
            Integer roundNo,
            Short cycle,
            LocalDate date,
            @Schema(description = "금요일 누적인가") boolean weekly,
            Short unitFrom,
            Short unitTo,
            @Schema(description = "이미 공개돼 저장된 회차인가. ★ 이 건은 바뀌지 않는다")
            boolean published) {

        public static Preview of(RoundScheduler.Round r, boolean published) {
            return new Preview(r.roundNo(), (short) r.cycle(), r.date(), r.weekly(),
                    (short) r.unitFrom(), (short) r.unitTo(), published);
        }
    }

    @Schema(name = "VocabPublishedRoundView", description = "공개된 회차")
    public record Round(
            Long id,
            Integer roundNo,
            Short cycle,
            LocalDate openDate,
            boolean weekly,
            Short unitFrom,
            Short unitTo) {

        public static Round from(VocabExamRound r) {
            return new Round(r.getId(), r.getRoundNo(), r.getCycle(), r.getOpenDate(),
                    r.isWeekly(), r.getUnitFrom(), r.getUnitTo());
        }
    }
}
