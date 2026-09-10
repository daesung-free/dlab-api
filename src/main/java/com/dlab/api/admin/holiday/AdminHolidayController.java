package com.dlab.api.admin.holiday;

import org.springframework.security.access.prepost.PreAuthorize;
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
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 공휴일 관리 (기초설정).
 *
 * <p>요구사항에 화면 정의가 없다 — 기초관리(F-4.10-1) 항목은
 * "학과·전형·반·강의실·사물함·장학"이고 공휴일이 빠져 있다.
 * 급식 가능일 계산이 이 데이터에 의존하므로 수기 입력 경로로 추가한 것이다.
 */
@Tag(name = "관리자 · 공휴일")
@RestController
@RequestMapping("/api/v1/admin/holidays")
@RequiredArgsConstructor
public class AdminHolidayController {

    private final HolidayService holidayService;

    /**
     * 기간 조회.
     *
     * <p>지점 계정은 <b>전 지점 공통 + 자기 지점</b>, 본사는 <b>전 지점</b>을 본다.
     * {@code academyId}를 주면 그 지점으로 좁힌다.
     */
    @GetMapping
    public ApiResponse<List<HolidayResponse>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NotNull LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @NotNull LocalDate to) {
        List<HolidayResponse> holidays = holidayService.findInRange(me, academyId, from, to).stream()
                .map(HolidayResponse::from)
                .toList();
        return ApiResponse.success(holidays);
    }

    /**
     * 공휴일 등록.
     *
     * <p><b>전 지점 공통 휴일은 본사만</b> 넣을 수 있다 — 지점 관리자가 넣으면
     * 다른 지점 급식까지 막힌다. 지점은 자기 지점 유형만 등록한다.
     *
     * <p>⚠️ 이 데이터는 <b>급식 가능일</b>용이다. 교습비 일수는 별개다 —
     * 학원은 삼일절·어린이날에도 운영한다.     *
     * <p><b>쓰기는 관리자만.</b> 지금까지 역할 검사가 없어 조회 전용(READONLY)과 행정선생님(STAFF)이
     * 휴일을 등록·수정·삭제할 수 있었다 — 지점 범위만 보고 역할은 보지 않았다.
     * 휴일 하나가 그 지점 급식 신청일을 통째로 바꾸므로 급식 중단일(AdminMealController)과
     * 같은 기준으로 맞춘다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @PostMapping
    public ApiResponse<HolidayResponse> register(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody HolidayRequest request) {
        var holiday = holidayService.register(
                me, request.academyId(), request.date(), request.name(), request.type(),
                request.planExcludedOrFalse());
        return ApiResponse.success(HolidayResponse.from(holiday));
    }

    /**
     * 이름과 <b>학습계획 차단 여부</b>를 고친다. 날짜·유형을 바꿀 일이면 지우고 새로 넣는 게
     * 이력상 명확하다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @PatchMapping("/{holidayId}")
    public ApiResponse<HolidayResponse> rename(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long holidayId,
            @Valid @RequestBody HolidayRequest request) {
        return ApiResponse.success(
                HolidayResponse.from(holidayService.rename(me, holidayId, request.name(),
                        request.planExcluded())));
    }

    /** 공휴일 삭제(soft). 물리 삭제하면 과거 급식 신청이 어느 규칙으로 계산됐는지 추적이 끊긴다. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @DeleteMapping("/{holidayId}")
    public ApiResponse<Void> remove(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long holidayId) {
        holidayService.remove(me, holidayId);
        return ApiResponse.empty();
    }
}
