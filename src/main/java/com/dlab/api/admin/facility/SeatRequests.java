package com.dlab.api.admin.facility;

import com.dlab.domain.facility.entity.AreaType;
import com.dlab.domain.facility.entity.SeatStyle;
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
     * @param areaCd        키오스크가 이 코드로 좌석을 조회한다. <b>등록 후 변경 불가</b>
     * @param areaType      {@code STUDY}(독서실, 기본) / {@code CLASSROOM}(반 교실).
     *                      키오스크에는 {@code STUDY}만 내려간다
     * @param classMasterId 반. {@code CLASSROOM}일 때 필수이고 {@code STUDY}면 보내지 않는다.
     *                      반 하나에 좌석표는 하나다
     */
    public record StudyAreaCreate(
            Long academyId,
            /**
             * 어느 관에 만들 것인가. <b>생략하면 본관</b>이다 — 관 개념이 새로 생긴 것이라
             * 화면이 아직 안 보낼 수 있는데, 그때 등록이 막히면 좌석 화면이 통째로 멈춘다.
             */
            Long buildingId,
            @NotBlank(message = "구역 코드는 필수입니다.")
            @Size(max = 50, message = "구역 코드는 50자까지입니다.") String areaCd,
            @NotBlank(message = "구역 이름은 필수입니다.")
            @Size(max = 100, message = "구역 이름은 100자까지입니다.") String areaNm,
            @Min(0) @Max(999) Short sortOrder,
            AreaType areaType,
            Long classMasterId) {

        public short sortOrderOrDefault() {
            return sortOrder == null ? (short) 0 : sortOrder;
        }

        /** 비우면 독서실이다 — 이 필드가 생기기 전 호출을 그대로 받기 위해서다. */
        public AreaType areaTypeOrDefault() {
            return areaType == null ? AreaType.STUDY : areaType;
        }
    }

    /** 구역 수정. {@code areaCd}는 대상이 아니다(키오스크 계약). */
    public record StudyAreaUpdate(
            @Size(max = 100, message = "구역 이름은 100자까지입니다.") String areaNm,
            @Min(0) @Max(999) Short sortOrder,
            Boolean active) {
    }

    /**
     * 좌석 단건 등록.
     *
     * @param seatStyle 좌석형태. <b>비우면 교실형</b>이다 — 1,637석이 교실형이라 대부분
     *                  보내지 않는다
     */
    public record SeatCreate(
            @NotNull(message = "구역은 필수입니다.") Long studyAreaId,
            @NotBlank(message = "좌석번호는 필수입니다.")
            @Size(max = 50, message = "좌석번호는 50자까지입니다.") String seatCd,
            @Size(max = 50, message = "좌석 이름은 50자까지입니다.") String seatNm,
            @Min(0) @NotNull(message = "X 좌표는 필수입니다.") Integer xPos,
            @Min(0) @NotNull(message = "Y 좌표는 필수입니다.") Integer yPos,
            SeatStyle seatStyle) {
    }

    /**
     * 격자 일괄 등록.
     *
     * <p>수십 석을 한 칸씩 등록하게 하면 실무에서 안 쓴다. 행·열과 시작 번호만 받아
     * 좌표·좌석번호를 서버가 만든다.
     *
     * @param seatCdPrefix  좌석번호 접두어. {@code "A-"} + 번호 → {@code A-01}.
     *                      <b>비워도 된다</b> — 그러면 번호만 남는다({@code 1}, {@code 2}…)
     * @param startNumber   시작 번호(기본 1). 기존 격자에 이어붙일 때 지정한다
     * @param numberPadding 번호 자릿수(기본 2). {@code 2}면 {@code 01}
     * @param startX        x 좌표 시작값(기본 1)
     * @param startY        y 좌표 시작값(기본 1)
     * @param columnMajor   세로 우선 번호매김(기본 가로 우선)
     * @param reverseX      가로 역방향 — 번호가 <b>오른쪽에서 왼쪽으로</b> 붙는다.
     *                      대구 A가 그렇다(1번이 오른쪽 끝)
     * @param reverseY      세로 역방향 — 번호가 <b>아래에서 위로</b> 붙는다.
     *                      동탄 별관 C가 가로·세로 둘 다 역방향이다
     * @param skips         통로 등 좌석이 없는 칸. 번호는 이 칸을 건너뛰고 이어진다
     * @param seatStyle     형태 범위에 들지 않는 좌석의 형태. 비우면 교실형
     * @param styleRanges   <b>번호 범위별 형태</b>. 한 구역에 형태가 섞여 있어
     *                      (광명 C는 3구간) 범위 목록으로 받는다 — 한 칸씩 찍게 하면
     *                      2,140석을 손으로 지정하게 된다
     */
    public record SeatGridCreate(
            @NotNull(message = "구역은 필수입니다.") Long studyAreaId,
            @Min(value = 1, message = "행은 1 이상이어야 합니다.") @Max(100) @NotNull(message = "행 수는 필수입니다.") Integer rows,
            @Min(value = 1, message = "열은 1 이상이어야 합니다.") @Max(100) @NotNull(message = "열 수는 필수입니다.") Integer columns,
            /*
             * ★ 필수가 아니다. 접두어 없는 **순수 숫자** 좌석번호가 정상 케이스다 —
             *   동탄이 그렇고, 별관 오프셋 변환(1번 → 1001번)도 숫자일 때만 걸린다.
             *   @NotBlank 였을 때 정작 이 기능이 필요한 지점이 격자를 못 만들었다.
             */
            @Size(max = 30, message = "접두어는 30자까지입니다.") String seatCdPrefix,
            @Min(0) Integer startNumber,
            @Min(0) @Max(6) Integer numberPadding,
            @Min(0) Integer startX,
            @Min(0) Integer startY,
            Boolean columnMajor,
            Boolean reverseX,
            Boolean reverseY,
            @Valid List<SeatGridSkipCell> skips,
            SeatStyle seatStyle,
            @Valid List<SeatStyleRange> styleRanges) {

        public String seatCdPrefixOrEmpty() {
            return seatCdPrefix == null ? "" : seatCdPrefix;
        }

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

        public boolean reverseXOrDefault() {
            return Boolean.TRUE.equals(reverseX);
        }

        public boolean reverseYOrDefault() {
            return Boolean.TRUE.equals(reverseY);
        }
    }

    /** 격자에서 비워 둘 칸. 행·열은 1부터 센다(화면에 보이는 그대로). */
    public record SeatGridSkipCell(
            @Min(value = 1, message = "행은 1부터입니다.") int row,
            @Min(value = 1, message = "열은 1부터입니다.") int column) {
    }

    /**
     * 번호 범위별 좌석형태.
     *
     * <p>디랩이 준 시트가 「구역 · 시작번호 · 끝번호 · 형태」 모양이라 <b>그대로 옮겨
     * 넣을 수 있게</b> 같은 형태로 받는다. 양 끝을 포함한다(213~253이면 253도 들어간다).
     *
     * @param fromNumber 시작 번호(포함)
     * @param toNumber   끝 번호(포함)
     */
    public record SeatStyleRange(
            @NotNull(message = "시작 번호는 필수입니다.") @Min(0) Integer fromNumber,
            @NotNull(message = "끝 번호는 필수입니다.") @Min(0) Integer toNumber,
            @NotNull(message = "좌석형태는 필수입니다.") SeatStyle seatStyle) {
    }

    /**
     * 이미 만들어 둔 좌석에 형태를 번호 범위로 입히기.
     *
     * <p>격자 등록 때 형태를 안 넣었거나, 시트가 나중에 온 구역을 위한 경로다. 좌석을
     * 다시 만들지 않으므로 <b>배정 이력이 끊기지 않는다.</b>
     *
     * @param defaultStyle 범위에 안 든 좌석을 어떻게 할지. <b>비우면 건드리지 않는다</b> —
     *                     일부 구간만 고치려는데 나머지가 교실형으로 덮이면 안 된다
     */
    public record SeatStyleApply(
            @NotNull(message = "구역은 필수입니다.") Long studyAreaId,
            @NotEmpty(message = "적용할 범위가 없습니다.")
            @Size(max = 100, message = "범위는 100개까지입니다.")
            @Valid List<SeatStyleRange> ranges,
            SeatStyle defaultStyle) {
    }

    /** 좌석 수정. {@code seatCd}는 대상이 아니다(키오스크가 이 코드로 좌석을 찾는다). */
    public record SeatUpdate(
            @Size(max = 50, message = "좌석 이름은 50자까지입니다.") String seatNm,
            @Min(0) Integer xPos,
            @Min(0) Integer yPos,
            SeatStyle seatStyle) {
    }
}
