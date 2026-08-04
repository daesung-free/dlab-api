package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DSA 응답 행. <b>필드명이 곧 계약이다</b> — 키오스크가 이 snake_case 키를 그대로 읽는다
 * ({@code doc/kiosk/…/DsaStudentData.fromMap} 등). camelCase로 "정리"하면 전부 null이 된다.
 *
 * <p><b>값은 전부 문자열이다.</b> 키오스크가 {@code String.valueOf()}로 받거나
 * {@code Number}면 {@code longValue()}로 눌러버린다 — 숫자로 내려도 어차피 문자열이 되고,
 * 좌표처럼 {@code parseInt} 하는 곳만 숫자를 허용한다. 원본 DSA 표기를 따라간다.
 */
public final class DsaRows {

    private DsaRows() {
    }

    /** {@code getStdInfoList} (3.20). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentRow(
            @JsonProperty("std_nm") String stdNm,
            @JsonProperty("std_no") String stdNo,
            @JsonProperty("rfid_no") String rfidNo,
            @JsonProperty("hp") String hp,
            @JsonProperty("seat_cd") String seatCd
    ) {
    }

    /**
     * {@code getStdInfo} (3.24).
     *
     * <p>키오스크는 응답 목록에서 <b>{@code std_no}로 한 번 더 필터</b>한 뒤
     * {@code hp}가 없으면 {@code p_hp}로 폴백해 <b>뒷 8자리</b>만 쓴다.
     * 그래서 {@code std_no}를 반드시 실어야 한다 — 빠지면 매칭에 실패해 전화번호가 null이 된다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentPhoneRow(
            @JsonProperty("std_no") String stdNo,
            @JsonProperty("hp") String hp,
            @JsonProperty("p_hp") String parentHp
    ) {
    }

    /** {@code getDlabList} (3.19). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AcademyRow(
            @JsonProperty("acad_cd") String acadCd,
            @JsonProperty("acad_nm") String acadNm,
            @JsonProperty("full_nm") String fullNm
    ) {
    }

    /**
     * {@code getParentHpList} (3.25).
     *
     * <p>⚠️ <b>{@code p_gb}는 성별이 아니라 관계다.</b> 키오스크가
     * {@code "F" → "부"}, {@code "M" → "모"}로 표시한다 — 즉 F=Father, M=Mother다.
     * 우리 {@code parent_guardian.gender}는 M=남/F=여라 <b>의미가 정반대로 겹친다.</b>
     * 그대로 넘기면 아버지가 "모"로 표시된다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ParentPhoneRow(
            @JsonProperty("p_gb") String pGb,
            @JsonProperty("p_hp") String pHp
    ) {
    }

    /** {@code getRequestListStd} (3.16). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RequestRow(
            @JsonProperty("reg_cd") String regCd,
            @JsonProperty("reg_dt") String regDt,
            @JsonProperty("reg_gn") String regGn
    ) {
    }

    /** {@code getStudyAreaInfo} (3.7). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AreaRow(
            @JsonProperty("area_cd") String areaCd,
            @JsonProperty("area_nm") String areaNm
    ) {
    }

    /**
     * {@code getStudyAreaSeatInfo} (3.8).
     *
     * <p><b>좌표 키를 {@code xpos}/{@code ypos}로 내린다.</b> 키오스크는
     * {@code xpos} → {@code x_pos} 순으로 둘 다 읽지만, 원본 DSA 표기를 유지한다
     * (docs/dsa-compat.md §2 — 정리하지 말 것).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeatRow(
            @JsonProperty("seat_cd") String seatCd,
            @JsonProperty("seat_nm") String seatNm,
            @JsonProperty("xpos") int xpos,
            @JsonProperty("ypos") int ypos,
            @JsonProperty("seat_gn") String seatGn
    ) {
    }

    /** {@code getStudyAreaSeatState} (3.10). {@code state}: {@code S}착석 {@code D}외출 {@code B}빈좌석. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeatStateRow(
            @JsonProperty("seat_cd") String seatCd,
            @JsonProperty("state") String state
    ) {
    }

    /** {@code getPointStdList} (3.28). 지점 전체를 내린다 — 학생 단건 조회가 아니다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PointRow(
            @JsonProperty("std_no") String stdNo,
            @JsonProperty("point_dt") String pointDt,
            @JsonProperty("reason") String reason,
            @JsonProperty("point") int point
    ) {
    }
}
