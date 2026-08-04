package com.dlab.api.admin.holiday.dto;

import com.dlab.domain.holiday.entity.HolidayType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 공휴일 등록 요청.
 *
 * @param academyId {@code null}이면 전 지점 공통(법정공휴일). 본사만 보낼 수 있다.
 *                  지점 자체 휴일이면 자기 지점 id
 * @param type      전 지점 공통이면 PUBLIC·SUBSTITUTE·TEMPORARY,
 *                  지점 휴일이면 ACADEMY만 허용된다
 */
public record HolidayRequest(
        Long academyId,
        @NotNull(message = "날짜는 필수입니다.") LocalDate date,
        @NotBlank(message = "휴일명은 필수입니다.") String name,
        @NotNull(message = "휴일 종류는 필수입니다.") HolidayType type
) {
}
