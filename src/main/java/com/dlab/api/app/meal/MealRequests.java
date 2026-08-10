package com.dlab.api.app.meal;

import com.dlab.domain.meal.entity.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** 앱 급식 요청 DTO. */
public final class MealRequests {

    private MealRequests() {
    }

    /**
     * 한 달치 일괄 신청.
     *
     * <p><b>한 건씩 신청하는 경로를 두지 않는다.</b> 도메인이 <i>전부-아니면-전무</i>로
     * 처리하는데(결제가 붙으면 금액이 한 덩어리로 확정돼야 한다), 한 건씩 받으면
     * 그 전제가 API 표면에서부터 깨진다.
     */
    public record Apply(
            @NotEmpty(message = "신청할 날짜를 선택해 주세요.") @Valid List<Selection> selections) {
    }

    public record Selection(
            @NotNull(message = "날짜는 필수입니다.") LocalDate date,
            @NotNull(message = "점심/저녁 구분은 필수입니다.") MealType mealType) {
    }
}
