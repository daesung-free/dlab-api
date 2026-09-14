package com.dlab.api.admin.facility;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관(본관/별관) 등록·수정. */
public final class BuildingRequests {

    private BuildingRequests() {
    }

    /**
     * 관 등록.
     *
     * @param seatCdOffset 키오스크에 내릴 좌석번호에 더할 값. <b>본관은 0</b>, 별관은
     *                     1000 이상. 등록 후에는 바꿀 수 없다 — 이미 만든 좌석의
     *                     키오스크 번호에 이 값이 반영돼 저장되기 때문이다
     */
    public record BuildingCreate(
            Long academyId,
            @NotBlank(message = "관 코드는 필수입니다.")
            @Size(max = 20, message = "관 코드는 20자까지입니다.") String code,
            @NotBlank(message = "관 이름은 필수입니다.")
            @Size(max = 100, message = "관 이름은 100자까지입니다.") String name,
            @Min(0) @Max(999) Short sortOrder,
            @Min(value = 0, message = "좌석번호 오프셋은 0 이상이어야 합니다.")
            @Max(value = 900_000, message = "좌석번호 오프셋이 너무 큽니다.") Integer seatCdOffset) {

        public short sortOrderOrDefault() {
            return sortOrder == null ? (short) 0 : sortOrder;
        }

        public int seatCdOffsetOrDefault() {
            return seatCdOffset == null ? 0 : seatCdOffset;
        }
    }

    /** 관 수정. {@code code}·{@code seatCdOffset}은 대상이 아니다. */
    public record BuildingUpdate(
            @Size(max = 100, message = "관 이름은 100자까지입니다.") String name,
            @Min(0) @Max(999) Short sortOrder,
            Boolean active) {
    }
}
