package com.dlab.api.appconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 입학동의서 최종본(2026-10-29) 약관 시드.
 *
 * <h2>★★ 운영에 약관이 하나도 없었다</h2>
 * 테스트용 {@code ZZ_TEST} 하나뿐이었다 — <b>앱 가입 화면이 보여줄 문구가 없는 상태</b>였다.
 *
 * <h2>★ 왜 DB 가 아니라 파일을 읽는가</h2>
 * 약관 행은 {@code db/seed} 에 있고 <b>Flyway 가 그 폴더를 읽지 않는다.</b>
 * 마이그레이션에 넣었다가 <b>기존 테스트 6건이 깨졌다</b> — 그 테스트들이 「약관 목록이
 * 자기가 만든 것뿐」을 전제로 {@code hasSize(1)} 로 단정하고, 같은 {@code code+version} 을
 * 또 만들려다 터진다. 지점 시드를 {@code db/seed} 에 둔 것과 같은 이유다.
 *
 * <p>★ 그래서 <b>시드 파일 자체가 최종본과 맞는가</b>를 본다. 지키려는 것은
 * <b>필수/선택</b>과 <b>문구를 요약하지 않았는가</b>이고, 둘 다 파일에 있다.
 *
 * <h2>★ 여기가 틀리면 무엇이 깨지나</h2>
 * 필수/선택이 틀리면 <b>거부할 수 있어야 하는 동의를 강제하거나</b>(가입이 막힌다)
 * <b>받아야 하는 동의를 건너뛴다</b>(법정 근거가 사라진다).
 */
class TermsFinalConsentTest {

    private static final Path SEED =
            Path.of("src/main/resources/db/seed/V20261030_1500__terms_final_consent.sql");

    private static String sql;

    @BeforeAll
    static void load() throws IOException {
        sql = Files.readString(SEED, StandardCharsets.UTF_8);
    }

    /** {@code SELECT '<code>', '1.0', ...} 뒤에 오는 {@code TRUE/FALSE} 가 필수 여부다. */
    private static boolean required(String code) {
        Matcher m = Pattern.compile(
                        "SELECT '" + code + "',.*?\\n\\s+(TRUE|FALSE), now\\(\\)", Pattern.DOTALL)
                .matcher(sql);
        assertThat(m.find()).as("약관 행이 없다: " + code).isTrue();
        return "TRUE".equals(m.group(1));
    }

    /**
     * 그 문구가 몇 번 나오나. ★ 주석 줄(--)은 세지 않는다 — 주석에 같은 말이 적혀 있어
     * 그대로 세면 12 가 나왔다.
     */
    private static int countOf(String needle) {
        return (int) sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .filter(line -> line.contains(needle))
                .count();
    }

    private static String content(String code) {
        int start = sql.indexOf("SELECT '" + code + "',");
        assertThat(start).as("약관 행이 없다: " + code).isNotNegative();
        int end = sql.indexOf("WHERE NOT EXISTS", start);
        return sql.substring(start, end);
    }

    @Test
    @DisplayName("★★ 최종본 11개 단위 중 10개가 시드에 있다 — 급식업체만 지점 매핑 대기다")
    void finalConsentRowsExist() {
        List<String> codes = List.of(
                "PRIVACY_REQUIRED", "PRIVACY_OPTIONAL", "ADMISSION_GUIDE", "CERTIFICATE",
                "MARKETING", "CCTV_NOTICE", "SENSITIVE", "GUARDIAN_ADMISSION",
                "PHONE_SUBMIT", "NO_SMOKING");

        assertThat(codes).allSatisfy(code ->
                assertThat(sql).as(code + " 행이 없다").contains("SELECT '" + code + "',"));
        assertThat(countOf("INSERT INTO terms"))
                .as("★ 10개여야 한다 — 늘거나 줄면 최종본과 어긋난 것이다")
                .isEqualTo(10);
    }

    /**
     * ★★ 민감정보는 <b>선택</b>이다.
     *
     * <p>최종본: 「동의를 거부할 수 있으며, 이 경우 관련 사유 및 증빙자료를 제출하지 않는
     * 대체 신청 방법을 안내합니다.」
     *
     * <p>⚠️ 필수로 두면 <b>거부한 학생은 가입 자체를 못 한다.</b> 건강 정보를 주지 않으면
     * 학원을 다닐 수 없게 되는데, 그건 최종본이 말하는 것과 반대다.
     */
    @Test
    @DisplayName("★★ 민감정보는 선택이다 — 필수로 두면 거부한 학생이 가입을 못 한다")
    void sensitiveIsOptional() {
        assertThat(required("SENSITIVE")).isFalse();
    }

    @Test
    @DisplayName("★ 선택 동의 넷 — 거부해도 불이익이 없다고 최종본이 적었다")
    void optionalConsents() {
        assertThat(required("PRIVACY_OPTIONAL")).isFalse();
        assertThat(required("ADMISSION_GUIDE")).isFalse();
        assertThat(required("CERTIFICATE")).isFalse();
        assertThat(required("MARKETING")).isFalse();
    }

    @Test
    @DisplayName("★ 필수 동의 — 거부하면 입학 등록이 제한된다")
    void requiredConsents() {
        assertThat(required("PRIVACY_REQUIRED")).isTrue();
        assertThat(required("GUARDIAN_ADMISSION")).isTrue();
        assertThat(required("PHONE_SUBMIT")).isTrue();
        assertThat(required("NO_SMOKING")).isTrue();
    }

    /**
     * ★★ 문구를 요약하지 않는다.
     *
     * <p>동의는 <b>「그때 어떤 문구에 동의했는가」</b>가 근거다. 요약해서 넣으면 그 근거가
     * 요약본이 되고, 나중에 다투면 원본을 들이댈 수 없다.
     */
    @Test
    @DisplayName("★★ 본문이 최종본 그대로다 — 요약하면 동의의 근거가 요약본이 된다")
    void contentIsVerbatim() {
        // 머리말 — 모든 약관에 붙어 있어야 한다(수집 방법·법 근거·보호자 서명)
        assertThat(content("PRIVACY_REQUIRED"))
                .contains("개인정보 보호법 제15조, 제17조 및 제22조")
                .contains("만19세 미만인 경우 학부모/보호자(법정대리인)가 함께 서명");

        // ★ 각 절의 고유 문구 — 다른 절이 들어갔으면 걸린다
        assertThat(content("SENSITIVE")).contains("증빙자료를 제출하지 않는 대체 신청 방법을 안내");
        assertThat(content("MARKETING")).contains("동의일부터 2년");
        assertThat(content("CERTIFICATE")).contains("보유 및 이용 기간 : 5년");
        assertThat(content("ADMISSION_GUIDE")).contains("등록일부터 다음 해 3월31일까지");
        assertThat(content("CCTV_NOTICE")).contains("영상정보의 보유 기간 : 30일");
    }

    /**
     * ⚠️ 급식업체는 <b>지점별</b>이어야 한다 — 최종본이 「해당 지점과 계약한 업체명만
     * 기재」다. 지점-업체 매핑을 받기 전에 전 지점 공통으로 넣으면 <b>업체 다섯 곳이 다
     * 적힌 동의서</b>가 되고, 학부모는 자기 자녀 정보가 어디로 가는지 알 수 없다.
     */
    @Test
    @DisplayName("⚠️ 급식업체 약관은 아직 넣지 않았다 — 지점별 업체명이 필요하다")
    void mealThirdPartyIsNotSeededYet() {
        assertThat(sql)
                .as("★ 지점-업체 매핑을 받은 뒤 academy_id 를 채워 넣는다")
                .doesNotContain("SELECT 'MEAL_THIRD_PARTY',");
    }

    /**
     * ★ 멱등해야 한다 — 손으로 돌리는 시드라 <b>여러 번 돌아간다.</b>
     *
     * <p>⚠️ {@code WHERE NOT EXISTS} 가 없으면 두 번째 실행에서 <b>같은 code+version 이
     * 둘</b>이 되고, 앱 동의 화면에 <b>같은 약관이 두 번 뜬다.</b>
     */
    @Test
    @DisplayName("★ 멱등하다 — 손으로 돌리는 시드라 여러 번 돌아간다")
    void seedIsIdempotent() {
        int inserts = countOf("INSERT INTO terms");
        int guards = countOf("WHERE NOT EXISTS");

        assertThat(guards)
                .as("★ INSERT 마다 중복 방지가 붙어 있어야 한다")
                .isEqualTo(inserts);
    }
}
