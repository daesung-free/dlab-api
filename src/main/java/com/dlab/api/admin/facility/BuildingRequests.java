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
     * @param annex        별관인가. <b>{@code seatCdOffset} 을 생략하면 이 값으로 갈린다</b> —
     *                     참이면 서버가 겹치지 않는 번호대를 채번하고, 거짓이면 본관(0)이다
     * @param seatCdOffset 키오스크에 내릴 좌석번호에 더할 값. <b>본관은 0</b>, 별관은
     *                     1000 이상. <b>생략하는 것을 권한다</b> — 화면이 계산해서 보내면
     *                     두 사람이 동시에 등록할 때 같은 값이 나온다.
     *                     등록 후에는 바꿀 수 없다(만든 좌석의 키오스크 번호에 이미 반영됐다)
     */
    public record BuildingCreate(
            Long academyId,
            @NotBlank(message = "관 코드는 필수입니다.")
            @Size(max = 20, message = "관 코드는 20자까지입니다.") String code,
            @NotBlank(message = "관 이름은 필수입니다.")
            @Size(max = 100, message = "관 이름은 100자까지입니다.") String name,
            @Min(0) @Max(999) Short sortOrder,
            Boolean annex,
            @Min(value = 0, message = "좌석번호 오프셋은 0 이상이어야 합니다.")
            @Max(value = 900_000, message = "좌석번호 오프셋이 너무 큽니다.") Integer seatCdOffset) {

        public short sortOrderOrDefault() {
            return sortOrder == null ? (short) 0 : sortOrder;
        }

        /**
         * 별관인가.
         *
         * <p>{@code annex} 를 안 보내는 옛 호출도 받아야 해서 <b>offset 값에서도 유추</b>한다 —
         * 0보다 큰 값을 보냈다면 별관을 만들겠다는 뜻이다.
         */
        public boolean isAnnex() {
            return Boolean.TRUE.equals(annex)
                    || (seatCdOffset != null && seatCdOffset > 0);
        }
    }

    /** 관 수정. {@code code}·{@code seatCdOffset}은 대상이 아니다. */
    public record BuildingUpdate(
            @Size(max = 100, message = "관 이름은 100자까지입니다.") String name,
            @Min(0) @Max(999) Short sortOrder,
            Boolean active) {
    }
}
