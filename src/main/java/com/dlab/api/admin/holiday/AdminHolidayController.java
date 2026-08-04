package com.dlab.api.admin.holiday;

import com.dlab.api.admin.holiday.dto.HolidayRequest;
import com.dlab.api.admin.holiday.dto.HolidayResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.holiday.service.HolidayService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공휴일 관리 (기초설정).
 *
 * <p>요구사항에 화면 정의가 없다 — 기초관리(F-4.10-1) 항목은
 * "학과·전형·반·강의실·사물함·장학"이고 공휴일이 빠져 있다.
 * 급식 가능일 계산이 이 데이터에 의존하므로 수기 입력 경로로 추가한 것이다.
 */
@RestController
@RequestMapping("/api/v1/admin/holidays")
@RequiredArgsConstructor
public class AdminHolidayController {

    private final HolidayService holidayService;

    /** 기간 조회. 전 지점 공통 + 내 지점 것이 함께 나온다. */
    @GetMapping
    public ApiResponse<List<HolidayResponse>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NotNull LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NotNull LocalDate to) {
        List<HolidayResponse> holidays = holidayService.findInRange(me, from, to).stream()
                .map(HolidayResponse::from)
                .toList();
        return ApiResponse.success(holidays);
    }

    @PostMapping
    public ApiResponse<HolidayResponse> register(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody HolidayRequest request) {
        var holiday = holidayService.register(
                me, request.academyId(), request.date(), request.name(), request.type());
        return ApiResponse.success(HolidayResponse.from(holiday));
    }

    /** 이름만 고친다. 날짜·유형을 바꿀 일이면 지우고 새로 넣는 게 이력상 명확하다. */
    @PatchMapping("/{holidayId}")
    public ApiResponse<HolidayResponse> rename(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long holidayId,
            @Valid @RequestBody HolidayRequest request) {
        return ApiResponse.success(
                HolidayResponse.from(holidayService.rename(me, holidayId, request.name())));
    }

    @DeleteMapping("/{holidayId}")
    public ApiResponse<Void> remove(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long holidayId) {
        holidayService.remove(me, holidayId);
        return ApiResponse.empty();
    }
}
