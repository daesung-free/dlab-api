package com.dlab.api.admin.tuition;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.service.TuitionPricingService;
import com.dlab.domain.user.entity.GradeType;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 교습비 가격·교습일수 관리 (F-4.10-5).
 *
 * <h2>0820 요청을 받는 화면이다</h2>
 * <i>"교습비를 입력하면 그달의 일수로 나누어 1일 교습비가 자동계산 되도록"</i> —
 * 지금까지 엑셀에 손으로 채우던 <b>할인 6단계 × 상품 5종 × 월 구분 4가지</b>가
 * <b>월 금액 두 칸 입력</b>으로 끝난다.
 *
 * <h2>★ 교습일수는 매년 넣어야 한다</h2>
 * 마이그레이션에 2026년분만 있다. 연도가 바뀌면 여기서 12칸을 새로 넣지 않는 한
 * <b>그 해 청구에서 1일 교습비를 계산할 수 없다</b>(`TEACHING_DAYS_NOT_REGISTERED`).
 * 기수 시작 전 체크리스트 항목이다.
 *
 * <h2>★ 전 지점 공통은 본사만</h2>
 * {@code academyId}를 비우면 공통 행이다. 지점 관리자가 이걸 고치면 나머지 지점 청구가
 * 같이 바뀌므로 막는다. 지점은 자기 지점 행만 만들 수 있고, 그 행이 있으면 그 지점에서는
 * 공통본 대신 그것이 쓰인다(목동·분당 재학생 가격이 이 방식이다).
 */
@Tag(name = "관리자 · 교습비 가격 (F-4.10-5)")
@RestController
@RequestMapping("/api/v1/admin/tuition")
@RequiredArgsConstructor
public class AdminTuitionController {

    private final TuitionPricingService pricingService;

    /**
     * 등록된 가격 목록.
     *
     * @param academyId 비우면 전 지점 공통 행. 지점 관리자는 자기 지점 ID를 넣어야 한다
     */
    @GetMapping("/prices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<TuitionRequests.PriceView>> prices(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) Long academyId) {

        return ApiResponse.success(pricingService.listPrices(me, year, academyId).stream()
                .map(TuitionRequests.PriceView::from).toList());
    }

    /**
     * 가격 등록·수정. 같은 (연도·학년·좌석유형)이 이미 있으면 <b>금액만 갱신</b>한다 —
     * 중복 행을 만들지 않는다.
     */
    @PutMapping("/prices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<TuitionRequests.PriceView> savePrice(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody TuitionRequests.SavePrice request) {

        return ApiResponse.success(TuitionRequests.PriceView.from(
                pricingService.savePrice(me, request.academyId(), request.year(),
                        request.gradeType(), request.seatType(),
                        request.tuitionFee(), request.studyRoomFee())));
    }

    /** 월별 교습일수 목록. 비어 있으면 그 해 청구가 계산되지 않는다. */
    @GetMapping("/months")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<TuitionRequests.MonthView>> months(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam(required = false) Long academyId) {

        return ApiResponse.success(pricingService.listMonths(me, year, academyId).stream()
                .map(TuitionRequests.MonthView::from).toList());
    }

    /**
     * 월별 교습일수 등록·수정.
     *
     * <p><b>달력 일수가 아니다.</b> 2026년 표 기준 2월이 27일, 9월이 29일이다 —
     * 서버는 달력을 <b>상한으로만</b> 확인하고(2월에 30을 넣으면 거부) 값 자체는
     * 학원이 아는 대로 받는다. 규칙을 추측해 자동 산출하면 조용히 틀린 금액이 나온다.
     */
    @PutMapping("/months")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<TuitionRequests.MonthView> saveMonth(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody TuitionRequests.SaveMonth request) {

        return ApiResponse.success(TuitionRequests.MonthView.from(
                pricingService.saveMonth(me, request.academyId(), request.year(),
                        request.month(), request.teachingDays())));
    }

    /**
     * 단가표 — 할인율별 1일 교습비·독서실비.
     *
     * <p>화면이 그대로 그리면 된다. <b>독서실비 열에는 할인이 적용되지 않는다</b> —
     * 어느 줄이든 정가 그대로다.
     */
    @GetMapping("/fee-table")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<TuitionRequests.FeeRow>> feeTable(
            @CurrentAccount AuthPrincipal me,
            @RequestParam short year,
            @RequestParam int month,
            @RequestParam GradeType gradeType,
            @RequestParam SeatType seatType,
            @RequestParam(required = false) Long academyId) {

        // 전 지점 권한자가 안 고르면 null(= 전 지점 공통가)이다.
        // 요청 값은 반드시 권한 검사를 거친다 — 그냥 믿으면 남의 지점 가격이 보인다
        Long scope = me.resolveAcademyScope(academyId);
        return ApiResponse.success(
                pricingService.feeTable(year, month, gradeType, seatType, scope).stream()
                        .map(TuitionRequests.FeeRow::from).toList());
    }
}
