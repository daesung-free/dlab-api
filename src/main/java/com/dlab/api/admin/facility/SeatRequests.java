package com.dlab.api.admin.facility;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 좌석·구역 관리자 요청 DTO.
 *
 * <p>★ record 단순명이 곧 OpenAPI 스키마 이름이다. 겹치면 <b>다른 엔드포인트가 잘못된
 * 스키마를 가리킨다</b> — 키오스크 구획에 이미 {@code AreaRequest}가 있으므로 그 이름을
 * 쓰지 않는다.
 */
public final class SeatRequests {

    private SeatRequests() {
    }

    public record SeatAssign(
            @NotNull(message = "좌석은 필수입니다.") Long seatId,
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId) {
    }

    /**
     * 일괄 배정.
     *
     * <p>전부-아니면-전무로 처리된다 — 하나라도 실패하면 아무것도 반영되지 않고 실패 사유가
     * 전부 모여서 돌아온다.
     */
    public record SeatBulkAssign(
            @NotEmpty(message = "배정할 건이 없습니다.")
            @Size(max = 500, message = "한 번에 500건까지 배정할 수 있습니다.")
            @Valid List<SeatAssign> items) {
    }

    /**
     * 구역 등록.
     *
     * @param areaCd 키오스크가 이 코드로 좌석을 조회한다. <b>등록 후 변경 불가</b>
     */
    public record StudyAreaCreate(
            Long academyId,
            @NotBlank(message = "구역 코드는 필수입니다.")
            @Size(max = 50, message = "구역 코드는 50자까지입니다.") String areaCd,
            @NotBlank(message = "구역 이름은 필수입니다.")
            @Size(max = 100, message = "구역 이름은 100자까지입니다.") String areaNm,
            @Min(0) @Max(999) Short sortOrder) {

        public short sortOrderOrDefault() {
            return sortOrder == null ? (short) 0 : sortOrder;
        }
    }

    /** 구역 수정. {@code areaCd}는 대상이 아니다(키오스크 계약). */
    public record StudyAreaUpdate(
            @Size(max = 100, message = "구역 이름은 100자까지입니다.") String areaNm,
            @Min(0) @Max(999) Short sortOrder,
            Boolean active) {
    }

    /** 좌석 단건 등록. */
    public record SeatCreate(
            @NotNull(message = "구역은 필수입니다.") Long studyAreaId,
            @NotBlank(message = "좌석번호는 필수입니다.")
            @Size(max = 50, message = "좌석번호는 50자까지입니다.") String seatCd,
            @Size(max = 50, message = "좌석 이름은 50자까지입니다.") String seatNm,
            @Min(0) @NotNull(message = "X 좌표는 필수입니다.") Integer xPos,
            @Min(0) @NotNull(message = "Y 좌표는 필수입니다.") Integer yPos) {
    }

    /**
     * 격자 일괄 등록.
     *
     * <p>수십 석을 한 칸씩 등록하게 하면 실무에서 안 쓴다. 행·열과 시작 번호만 받아
     * 좌표·좌석번호를 서버가 만든다.
     *
     * @param seatCdPrefix  좌석번호 접두어. {@code "A-"} + 번호 → {@code A-01}
     * @param startNumber   시작 번호(기본 1). 기존 격자에 이어붙일 때 지정한다
     * @param numberPadding 번호 자릿수(기본 2). {@code 2}면 {@code 01}
     * @param startX        x 좌표 시작값(기본 1)
     * @param startY        y 좌표 시작값(기본 1)
     * @param columnMajor   세로 우선 번호매김(기본 가로 우선)
     * @param skips         통로 등 좌석이 없는 칸. 번호는 이 칸을 건너뛰고 이어진다
     */
    public record SeatGridCreate(
            @NotNull(message = "구역은 필수입니다.") Long studyAreaId,
            @Min(value = 1, message = "행은 1 이상이어야 합니다.") @Max(100) @NotNull(message = "행 수는 필수입니다.") Integer rows,
            @Min(value = 1, message = "열은 1 이상이어야 합니다.") @Max(100) @NotNull(message = "열 수는 필수입니다.") Integer columns,
            @NotBlank(message = "좌석번호 접두어는 필수입니다.")
            @Size(max = 30, message = "접두어는 30자까지입니다.") String seatCdPrefix,
            @Min(0) Integer startNumber,
            @Min(0) @Max(6) Integer numberPadding,
            @Min(0) Integer startX,
            @Min(0) Integer startY,
            Boolean columnMajor,
            @Valid List<SeatGridSkipCell> skips) {

        public int startNumberOrDefault() {
            return startNumber == null ? 1 : startNumber;
        }

        public int numberPaddingOrDefault() {
            return numberPadding == null ? 2 : numberPadding;
        }

        public int startXOrDefault() {
            return startX == null ? 1 : startX;
        }

        public int startYOrDefault() {
            return startY == null ? 1 : startY;
        }

        public boolean columnMajorOrDefault() {
            return Boolean.TRUE.equals(columnMajor);
        }
    }

    /** 격자에서 비워 둘 칸. 행·열은 1부터 센다(화면에 보이는 그대로). */
    public record SeatGridSkipCell(
            @Min(value = 1, message = "행은 1부터입니다.") int row,
            @Min(value = 1, message = "열은 1부터입니다.") int column) {
    }

    /** 좌석 수정. {@code seatCd}는 대상이 아니다(키오스크가 이 코드로 좌석을 찾는다). */
    public record SeatUpdate(
            @Size(max = 50, message = "좌석 이름은 50자까지입니다.") String seatNm,
            @Min(0) Integer xPos,
            @Min(0) Integer yPos) {
    }
}
