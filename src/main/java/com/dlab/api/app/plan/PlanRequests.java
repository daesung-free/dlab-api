package com.dlab.api.app.plan;

import com.dlab.domain.plan.entity.LearningPlanOptionType;
import com.dlab.domain.plan.service.LearningPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalTime;
import java.util.List;

public final class PlanRequests {

    private PlanRequests() {}

    /**
     * 하루치 통째 저장.
     *
     * <p><b>순번을 받지 않는다.</b> 서버가 시작시각 순으로 매긴다 — 클라이언트가 보낸 번호를
     * 그대로 쓰면 화면에서 정렬만 바꿔도 순번이 시각과 어긋난다.
     */
    public record SaveDay(
            @NotNull @Valid List<PlanItemInput> items) {

        public List<LearningPlanService.ItemCommand> toCommands() {
            return items.stream()
                    .map(i -> new LearningPlanService.ItemCommand(
                            i.startTime(), i.durationMinutes(),
                            i.subjectOptionId(), i.studyTypeOptionId(), i.material()))
                    .toList();
        }
    }

    public record PlanItemInput(
            @NotNull LocalTime startTime,
            /* 상한 720분은 오타(6000분 등)로 통계가 통째로 망가지는 것을 막는다 */
            @Min(1) @Max(720) short durationMinutes,
            @NotNull Long subjectOptionId,
            @NotNull Long studyTypeOptionId,
            @Size(max = 200) String material) {}

    /** 이행 O/X. 되돌릴 수 있어야 해서 boolean으로 받는다. */
    public record Mark(@NotNull Boolean done) {}

    public record SaveOption(
            @NotNull LearningPlanOptionType optionType,
            @NotBlank @Size(max = 30) String label,
            short sortOrder) {}
}
