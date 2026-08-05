package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code getMealApplyYN} 요청 (규격서 3.31).
 *
 * @param mealGb 식사 구분 — <b>요청은 {@code L}/{@code D}</b>. 응답은 {@code "점심"}/{@code "저녁"}이라
 *               표기가 비대칭이다(CLAUDE.md §7 — 통일하지 말 것)
 */
public record MealApplyRequest(
        @JsonProperty("token") String token,
        @JsonProperty("rfid_no") String rfidNo,
        @JsonProperty("meal_gb") String mealGb
) implements KioskRequest {
}
