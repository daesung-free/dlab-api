package com.dlab.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쿼리 파라미터 타입 불일치가 400으로 나가는지.
 *
 * <p><b>왜 테스트로 고정하나</b> — 이건 처리하지 않으면 조용히 <b>500</b>이 된다.
 * {@code ?year=abc} 같은 클라이언트 오타 하나에 "서버 오류"가 돌아오면, 앱·웹 개발자가
 * 자기 요청이 아니라 서버를 의심하며 시간을 쓴다. 핸들러가 지워지거나 catch-all보다
 * 아래로 밀려도 컴파일은 통과하므로, 상태코드를 테스트로 못박아 둔다.
 *
 * <p>enum은 <b>허용값 목록이 응답에 보이는지</b>까지 본다 — 그게 없으면 화면은
 * "값이 올바르지 않다"는 것만 알고 무엇이 올바른지는 모른다.
 */
class TypeMismatchHandlingTest {

    enum Track { SCIENCE, LIBERAL_ARTS }

    @RestController
    static class StubController {

        @GetMapping("/api/v1/test/type-mismatch")
        String byYear(@RequestParam short year) {
            return "ok";
        }

        /** 올바른 방식 — 바인딩 단계에서 형식을 본다. */
        @GetMapping("/api/v1/test/year-month")
        String byMonth(@RequestParam
                       @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM")
                       java.time.YearMonth month) {
            return "ok";
        }

        /** 예전 방식 — String 으로 받아 본문에서 파싱한다. 안전망 확인용이다. */
        @GetMapping("/api/v1/test/parsed-in-body")
        String parsedInBody(@RequestParam String month) {
            return java.time.YearMonth.parse(month).toString();
        }

        @GetMapping("/api/v1/test/enum-mismatch")
        String byTrack(@RequestParam Track track) {
            return "ok";
        }

        @PostMapping("/api/v1/test/body")
        String body(@RequestBody Payload payload) {
            return "ok";
        }
    }

    /** 중첩·배열까지 경로가 나오는지 보려고 한 겹 더 둔다. */
    record Payload(java.time.Instant applyFrom, Track track, java.util.List<Item> items) {
    }

    record Item(int amount) {
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StubController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("숫자 파라미터에 문자열을 넣으면 500이 아니라 400이고, 어느 파라미터인지 응답에 담긴다")
    void nonNumericValueForNumericParamIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/test/type-mismatch").param("year", "abc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("year")));
    }

    @Test
    @DisplayName("enum에 없는 값을 넣으면 400이고, 응답에 허용값 목록이 보인다")
    void unknownEnumValueIsBadRequestAndListsAllowedValues() throws Exception {
        mvc.perform(get("/api/v1/test/enum-mismatch").param("track", "NATURAL")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("track")))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("SCIENCE")))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("LIBERAL_ARTS")));
    }

    @Test
    @DisplayName("긴 입력값은 잘라서 싣는다 — 응답 메시지가 사용자 입력으로 도배되지 않는다")
    void longValueIsAbbreviated() throws Exception {
        String longValue = "x".repeat(300);
        mvc.perform(get("/api/v1/test/type-mismatch").param("year", longValue)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("...")))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(longValue))));
    }

    @Test
    @DisplayName("★ yyyy-MM 이 아닌 month 는 400이다 — /admin/meals/monthly?month=9 가 500이었다")
    void yearMonthBindingFails() throws Exception {
        mvc.perform(get("/api/v1/test/year-month").param("month", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("month")));
    }

    @Test
    @DisplayName("★★ 본문에서 파싱하다 터져도 500으로 새어나가지 않는다 — 안전망")
    void parsedInBodyStillReturns400() throws Exception {
        mvc.perform(get("/api/v1/test/parsed-in-body").param("month", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("올바른 형식은 통과한다")
    void validYearMonthPasses() throws Exception {
        mvc.perform(get("/api/v1/test/year-month").param("month", "2026-09"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 본문 형식 오류에 어느 필드인지 담긴다 — 없으면 본문 전체를 놓고 원인을 찾아야 한다")
    void unreadableBodyNamesTheField() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applyFrom\":\"2026-09-01T00:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("applyFrom")));
    }

    @Test
    @DisplayName("본문 enum 도 허용값 목록을 내린다 — 파라미터와 같은 기준이다")
    void unreadableBodyListsEnumValues() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"track\":\"NATURAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("track")))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("SCIENCE")));
    }

    @Test
    @DisplayName("배열 안쪽 필드도 경로로 찍힌다 — items[0].amount")
    void unreadableBodyShowsNestedPath() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"amount\":\"abc\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("items")))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("amount")));
    }

    @Test
    @DisplayName("본문 예외 원문은 싣지 않는다 — 엔티티 패키지 경로가 그대로 들어 있다")
    void unreadableBodyHidesInternalTypes() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applyFrom\":\"2026-09-01T00:00:00\"}"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("com.dlab"))));
    }
}
