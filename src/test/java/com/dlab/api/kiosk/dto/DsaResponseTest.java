package com.dlab.api.kiosk.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.api.kiosk.DsaCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 직렬화 형태를 고정한다. 여기가 깨지면 키오스크 파싱이 통째로 깨지는데,
 * 런타임에는 조용히 실패하므로(키오스크 폴백이 가려버린다) 테스트로 못 박아둔다.
 */
class DsaResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("성공 응답은 code=0이고 data가 단일 배열이다")
    void okIsFlatArray() {
        String json = mapper.writeValueAsString(
                DsaResponse.ok(List.of(Map.of("acad_cd", "31"))));

        assertThat(json).contains("\"code\":0");
        assertThat(json).contains("\"data\":[{\"acad_cd\":\"31\"}]");
    }

    @Test
    @DisplayName("이중 배열 엔드포인트는 [[{...}]] 형태를 유지한다")
    void nestedStaysNested() {
        String json = mapper.writeValueAsString(
                DsaResponse.okNested(List.of(Map.of("seat_cd", "A-24"))));

        assertThat(json).contains("\"data\":[[{\"seat_cd\":\"A-24\"}]]");
    }

    @Test
    @DisplayName("부가 필드는 data 안이 아니라 최상위에 실린다")
    void extraGoesTopLevel() {
        String json = mapper.writeValueAsString(
                DsaResponse.ok(List.of()).with("total_inwon", "312"));

        assertThat(json).contains("\"total_inwon\":\"312\"");
        // data 안으로 들어가면 클라이언트가 못 읽는다
        assertThat(json).contains("\"data\":[]");
    }

    @Test
    @DisplayName("본문 없는 성공도 data는 null이 아니라 빈 배열이다")
    void emptyIsArrayNotNull() {
        String json = mapper.writeValueAsString(DsaResponse.ok());

        assertThat(json).contains("\"data\":[]");
        assertThat(json).doesNotContain("null");
    }

    @Test
    @DisplayName("실패도 code로만 표현한다 — HTTP 상태코드가 아니다")
    void errorCarriesCodeOnly() {
        String json = mapper.writeValueAsString(DsaResponse.error(DsaCode.TOKEN_EXPIRED));

        assertThat(json).contains("\"code\":910");
        assertThat(DsaResponse.error(DsaCode.TOKEN_EXPIRED).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("data 내부에 실어야 하는 코드와 최상위 코드가 구분된다")
    void codePlacementIsDeclared() {
        assertThat(DsaCode.TOKEN_EXPIRED.atTopLevel()).isTrue();
        assertThat(DsaCode.ALREADY_LEFT_EARLY.atTopLevel()).isTrue();
        assertThat(DsaCode.NO_TIMETABLE.atTopLevel()).isFalse();
        assertThat(DsaCode.OUTSIDE_STUDY_HOURS.atTopLevel()).isFalse();
    }
}
