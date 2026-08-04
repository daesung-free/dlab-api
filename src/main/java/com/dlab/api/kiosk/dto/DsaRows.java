package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DSA 응답 행. <b>필드명·값 형식이 곧 계약이다.</b>
 *
 * <p><b>정본은 {@code DSA_Kiosk 연동규격서.doc}</b>(시너지개발실, V1.0)이고,
 * 키오스크 백엔드 소스({@code doc/kiosk/…})는 "실제로 어떻게 읽는지"를 확인하는 보조 자료다.
 * 둘이 갈리면 규격서를 따르되, 키오스크가 못 읽는 형태는 피한다.
 *
 * <p><b>값은 전부 문자열이다.</b> 규격서 샘플이 좌표·점수까지 {@code "1"}·{@code "-1"}처럼
 * 따옴표로 싣는다. 키오스크도 {@code String.valueOf} 또는 {@code parseInt}로 받으므로
 * 문자열이 안전하다.
 *
 * <p><b>개인정보는 마스킹해서 내린다</b> — 규격서 샘플 자체가 {@code "홍*동"}·{@code hp "1521"}
 * (뒷 4자리)로 되어 있다. 키오스크는 여러 학생이 오가는 공용 화면이라 원본을 띄우면 안 된다.
 */
public final class DsaRows {

    private DsaRows() {
    }

    /**
     * {@code getStdInfoList} (3.20).
     *
     * <p>규격서: 이름은 {@code "홍*동"}, {@code hp}는 <b>휴대폰 번호 뒷자리</b>({@code "1521"}).
     */
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
     * {@code getStdInfo} (3.24) — 학생 상세.
     *
     * <p>규격서 필드는 {@code std_nm}·{@code std_no}·{@code hp}·{@code zip}·{@code addr1}·{@code addr2}다.
     * 여기 {@code hp}는 목록과 달리 <b>전체 번호</b>({@code "010-1111-2222"})다 —
     * 카드를 태깅한 본인 한 명만 나오는 화면이라 구분된다.
     *
     * <p>{@code p_hp}는 규격서에 없는 확장이다. 키오스크가 {@code hp ?? p_hp}로 폴백하도록
     * 짜여 있어(그쪽 {@code DsaStudentService}), 본인 번호가 없는 재원생을 위해 함께 싣는다.
     * 규격서에 없는 키라 무시돼도 무해하다.
     *
     * <p>주소({@code zip}·{@code addr1}·{@code addr2})는 <b>우리 스키마에 없어 항상 null</b>이다 —
     * 키오스크도 읽지 않는다(그쪽은 {@code hp}만 꺼낸다). 필드는 계약 유지를 위해 남긴다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentDetailRow(
            @JsonProperty("std_nm") String stdNm,
            @JsonProperty("std_no") String stdNo,
            @JsonProperty("hp") String hp,
            @JsonProperty("p_hp") String parentHp,
            @JsonProperty("zip") String zip,
            @JsonProperty("addr1") String addr1,
            @JsonProperty("addr2") String addr2
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
     * <p>⚠️ <b>{@code p_gb}는 성별이 아니라 관계다</b> — 규격서: 부 {@code F} / 모 {@code M} /
     * 기타 {@code E}. 우리 {@code parent_guardian.gender}는 M=남/F=여라
     * <b>글자가 정반대로 겹친다.</b> 그대로 넘기면 아버지가 "모"로 표시된다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ParentPhoneRow(
            @JsonProperty("p_gb") String pGb,
            @JsonProperty("p_hp") String pHp
    ) {
    }

    /**
     * {@code getRequestListStd} (3.16).
     *
     * <p>규격서는 각 행에 {@code hak_no}·{@code std_nm}도 반복해서 싣고,
     * 최상위에도 같은 값을 둔다. 중복이지만 그대로 따른다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RequestRow(
            @JsonProperty("hak_no") String hakNo,
            @JsonProperty("std_nm") String stdNm,
            @JsonProperty("reg_cd") String regCd,
            @JsonProperty("reg_dt") String regDt,
            @JsonProperty("reg_gn") String regGn,
            @JsonProperty("state") String state
    ) {
    }

    /** {@code getStudyAreaInfo} (3.7). {@code area_inwon}은 구역 총 수용인원(좌석 수). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AreaRow(
            @JsonProperty("area_cd") String areaCd,
            @JsonProperty("area_nm") String areaNm,
            @JsonProperty("area_inwon") String areaInwon
    ) {
    }

    /**
     * {@code getStudyAreaSeatInfo} (3.8).
     *
     * <p><b>좌표 키는 {@code x_pos}/{@code y_pos}다</b>(규격서 표기). 키오스크는
     * {@code xpos} → {@code x_pos} 순으로 둘 다 읽지만 정본을 따른다.
     *
     * <p>{@code seat_gn}은 <b>3값</b>이다 — {@code Y} 사용 / {@code N} 미사용 / {@code E} 통로.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeatRow(
            @JsonProperty("seat_cd") String seatCd,
            @JsonProperty("x_pos") String xPos,
            @JsonProperty("y_pos") String yPos,
            @JsonProperty("seat_nm") String seatNm,
            @JsonProperty("seat_gn") String seatGn
    ) {
    }

    /**
     * {@code getStudyAreaSeatState} (3.10).
     *
     * <p><b>{@code state}는 4값이다</b> — {@code S} 등원 / {@code D} 외출 /
     * {@code N} 미출석 / {@code B} 공석. <b>{@code N}과 {@code B}는 다르다</b>:
     * 배정된 학생이 아직 안 온 자리가 {@code N}, 아무도 배정되지 않은 자리가 {@code B}다.
     * 둘을 합치면 좌석표에서 "결석자 자리"와 "빈 자리"가 구분되지 않는다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeatStateRow(
            @JsonProperty("seat_cd") String seatCd,
            @JsonProperty("state") String state
    ) {
    }

    /**
     * {@code getPointStdList} (3.28).
     *
     * <p>규격서 샘플이 {@code "point" : "-1"} — <b>벌점은 음수 문자열</b>이다.
     * 지점 전체를 내린다(학생 단건 아님).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PointRow(
            @JsonProperty("point_dt") String pointDt,
            @JsonProperty("std_nm") String stdNm,
            @JsonProperty("std_no") String stdNo,
            @JsonProperty("reason") String reason,
            @JsonProperty("point") String point
    ) {
    }
}
