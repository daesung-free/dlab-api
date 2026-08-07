package com.dlab.api.admin.period;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.period.service.PeriodService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 교시·시간 편집 (F-4.10-1).
 *
 * <p><b>전용 화면은 없다</b>(0803 폐기) — 편집은 학습계획 화면 안에서 이뤄진다.
 *
 * <p>이 마스터를 출결 판정·순공시간·학습계획이 함께 쓴다. 그래서 겹치는 교시를 만들 수 없고,
 * 마지막 하나를 지울 수 없다 — 교시가 0개인 날은 "운영일 아님"이 돼 태깅이 전원 거부된다.
 */
@RestController
@RequestMapping("/api/v1/admin/periods")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminPeriodController {

    private final PeriodService periodService;

    /**
     * 교시 목록.
     *
     * @param academyId 전 지점 권한자만 지정한다. 지점 관리자는 자기 지점으로 고정된다
     * @param dayType   없으면 평일·토요일을 함께 내린다
     */
    @GetMapping
    public ApiResponse<List<PeriodResponse>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam(required = false) DayType dayType) {

        List<PeriodMaster> periods = dayType == null
                ? periodService.findAll(me, academyId, year)
                : periodService.findByDayType(me, academyId, year, dayType);

        return ApiResponse.success(periods.stream().map(PeriodResponse::from).toList());
    }

    @PostMapping
    public ApiResponse<PeriodResponse> create(@CurrentAccount AuthPrincipal me,
                                              @Valid @RequestBody PeriodRequest request) {
        return ApiResponse.success(PeriodResponse.from(periodService.create(
                me, request.academyId(), request.year(), request.dayType(),
                request.periodNo(), request.name(), request.periodType(),
                request.startTime(), request.endTime(),
                request.planable(), request.mandatory())));
    }

    /** <b>요일 구분은 못 바꾼다.</b> 옮기면 양쪽 구성이 동시에 깨진다 — 지우고 새로 만든다. */
    @PutMapping("/{id}")
    public ApiResponse<PeriodResponse> update(@CurrentAccount AuthPrincipal me,
                                              @PathVariable Long id,
                                              @Valid @RequestBody PeriodUpdateRequest request) {
        return ApiResponse.success(PeriodResponse.from(periodService.update(
                me, id, request.periodNo(), request.name(), request.periodType(),
                request.startTime(), request.endTime(),
                request.planable(), request.mandatory())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        periodService.delete(me, id);
        return ApiResponse.empty();
    }

    /**
     * @param periodType MEAL·BREAK는 순공시간에서 빠진다. 시각 상수가 아니라
     *                   이 값이 유일한 출처다
     * @param planable   학습계획 입력 허용. 0723 확정으로 점심·저녁도 허용이라 기본 참이다
     */
    public record PeriodRequest(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotNull(message = "요일 구분은 필수입니다.") DayType dayType,
            @NotNull(message = "교시 번호는 필수입니다.") @Positive Short periodNo,
            @Size(max = 30) String name,
            @NotNull(message = "교시 유형은 필수입니다.") PeriodType periodType,
            @NotNull(message = "시작 시각은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @NotNull(message = "종료 시각은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            boolean planable,
            boolean mandatory) {
    }

    public record PeriodUpdateRequest(
            @NotNull(message = "교시 번호는 필수입니다.") @Positive Short periodNo,
            @Size(max = 30) String name,
            @NotNull(message = "교시 유형은 필수입니다.") PeriodType periodType,
            @NotNull(message = "시작 시각은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @NotNull(message = "종료 시각은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            boolean planable,
            boolean mandatory) {
    }

    public record PeriodResponse(
            Long id,
            short year,
            DayType dayType,
            short periodNo,
            String name,
            PeriodType periodType,
            LocalTime startTime,
            LocalTime endTime,
            boolean planable,
            boolean mandatory) {

        public static PeriodResponse from(PeriodMaster p) {
            return new PeriodResponse(p.getId(), p.getYear(), p.getDayType(), p.getPeriodNo(),
                    p.getName(), p.getPeriodType(), p.getStartTime(), p.getEndTime(),
                    p.isPlanable(), p.isMandatory());
        }
    }
}
