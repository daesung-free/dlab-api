package com.dlab.api.kiosk.meal;

import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.api.kiosk.dto.MealApplyRequest;
import com.dlab.api.kiosk.dto.MonthRequest;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskMealQueryService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 급식 조회.
 *
 * <p><b>이 컨트롤러가 죽으면 급식이 조용히 전부 통과한다</b> — 키오스크가 호출 실패 시
 * 허용으로 폴백하기 때문이다. 모니터링 대상 1순위다(CLAUDE.md §3).
 */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskMealController {

    private final KioskMealQueryService mealQueryService;
    private final DsaTokenService tokenService;

    /** 3.31 — 금일 급식 신청 여부. 최상위 {@code meal_yn}. */
    @PostMapping("/getMealApplyYN")
    public DsaResponse mealApplyYn(@RequestBody MealApplyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        boolean applied = mealQueryService.isApplied(
                academyId, request.rfidNo(), request.mealGb());

        return DsaResponse.ok().with("meal_yn", applied ? "Y" : "N");
    }

    /** 3.30 — 월별 신청 내역. {@code day}는 <b>일만</b> 넣는다. */
    @PostMapping("/getMealApplyStdInfo")
    public DsaResponse mealApplyStdInfo(@RequestBody MonthRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());

        return DsaResponse.ok(
                mealQueryService.monthlyApplications(academyId, request.month()).stream()
                        .map(r -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("std_nm", r.stdNm());
                            row.put("std_no", r.stdNo());
                            row.put("day", r.day());
                            row.put("meal_gb", r.mealGb());
                            return row;
                        })
                        .toList());
    }
}
