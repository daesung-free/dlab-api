package com.dlab.api.kiosk.dto;

import com.dlab.api.kiosk.DsaCode;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSA 호환 응답 껍데기. <b>{@code ApiResponse}와 절대 섞지 말 것</b> —
 * 키오스크는 이 형태만 파싱한다(CLAUDE.md §7).
 *
 * <pre>
 * { "code": 0, "message": "...", "data": [ {...} ], "total_inwon": "123" }
 * </pre>
 *
 * <p>주의할 점 세 가지. 전부 의도된 것이라 "정리"하면 키오스크가 깨진다.
 * <ol>
 *   <li><b>성공 판정은 {@code code == 0}</b>이다. HTTP 상태코드가 아니다 —
 *       실패해도 200으로 내려간다.</li>
 *   <li><b>{@code data}는 항상 배열</b>이고, 엔드포인트에 따라
 *       {@code [{...}]} 또는 {@code [[{...}]]} 이중 배열이다.
 *       실수로 섞이지 않게 {@link #ok}/{@link #okNested}로 의도를 드러낸다.</li>
 *   <li><b>부가 필드는 최상위에 흩뿌려진다</b>({@code total_inwon}·{@code study_tm} 등).
 *       {@code data} 안으로 옮기면 클라이언트가 못 읽는다.</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "message", "data"})
public final class DsaResponse {

    @JsonProperty("code")
    private final int code;

    @JsonProperty("message")
    private final String message;

    /** 항상 배열. null이 아니라 빈 배열로 내려야 클라이언트 분기가 단순해진다. */
    @JsonProperty("data")
    private final Object data;

    private final Map<String, Object> extra = new LinkedHashMap<>();

    private DsaResponse(int code, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 단일 배열 {@code [{...}]}. 대부분의 엔드포인트가 이 형태다. */
    public static DsaResponse ok(List<?> data) {
        return new DsaResponse(DsaCode.SUCCESS.value(), DsaCode.SUCCESS.defaultMessage(),
                data == null ? List.of() : data);
    }

    /** 이중 배열 {@code [[{...}]]}. 이 형태로 오던 엔드포인트만 사용할 것. */
    public static DsaResponse okNested(List<?> data) {
        return new DsaResponse(DsaCode.SUCCESS.value(), DsaCode.SUCCESS.defaultMessage(),
                List.of(data == null ? List.of() : data));
    }

    /** 본문 없이 성공만 알리는 엔드포인트(setSeatChgProc·setReAttendProc 등). */
    public static DsaResponse ok() {
        return ok(List.of());
    }

    public static DsaResponse error(DsaCode code) {
        return error(code, code.defaultMessage());
    }

    public static DsaResponse error(DsaCode code, String message) {
        return new DsaResponse(code.value(), message, List.of());
    }

    /**
     * 최상위 부가 필드를 추가한다.
     * 예: {@code getTotalAttendCount}의 {@code total_inwon}, {@code getStudyTimeList}의 {@code study_tm}.
     */
    public DsaResponse with(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }

    public boolean isSuccess() {
        return code == DsaCode.SUCCESS.value();
    }
}
