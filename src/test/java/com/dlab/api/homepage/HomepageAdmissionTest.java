package com.dlab.api.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.admission.entity.CommonCode;
import com.dlab.domain.admission.repository.AdmissionReservationRepository;
import com.dlab.domain.admission.service.HomepageTokenService;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 홈페이지 입학예약 연동 (규격서 3.1~3.8).
 *
 * <p>홈페이지가 대성전산을 부르던 자리에 우리가 앉는다. <b>응답 형식과 학원코드 체계가
 * 키오스크와 다르다</b> — 여기서 어긋나면 홈페이지가 본문을 못 읽거나 엉뚱한 지점에 저장된다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "homepage.integration.client-id=dsisa1",
        "homepage.integration.secret=test-secret"
})
class HomepageAdmissionTest {

    @Autowired WebApplicationContext context;
    @Autowired HomepageTokenService tokenService;
    @Autowired DsaTokenService dsaTokenService;
    @Autowired AdmissionReservationRepository reservationRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    /** 첨부는 S3 로 나간다 — 테스트에서 실제로 올리지 않는다. 저장 키만 검증한다. */
    @MockitoBean
    com.dlab.domain.file.service.FileStorage fileStorage;

    MockMvc mvc;
    Academy bundang;
    String token;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        em.createNativeQuery("UPDATE academy SET dlab_cd = 'F' WHERE id = :id")
                .setParameter("id", bundang.getId()).executeUpdate();

        em.persist(new CommonCode(CommonCode.GRP_SUBJECT, "10", "화법과작문",
                (short) 1, null, (short) 1));
        em.persist(new CommonCode(CommonCode.GRP_EXAM, "1229", "윈터스쿨",
                null, "2026", null, (short) 1));
        em.flush();

        token = tokenService.issue("dsisa1", secretOf("test-secret"), "dlab").token();
    }

    private String secretOf(String secret) {
        try {
            String raw = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + secret;
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String saveOne() throws Exception {
        String body = """
                {"token":"%s","acid":"F","reg_yyyy":"2026","rsv_nm":"홍길동",
                 "std_tel":"01012345678","par_tel":"01098765432","gender_gb":"M",
                 "birth":"20081106","geyul_gb":9,"adm_dt":"20261014","pre_test":1229,
                 "admi_st":1919,"find_gb":99,"find_txt":"배너 광고","sch_cd":1001,
                 "zip":"06572","addr1":"서울 서초구 방배로 181","addr2":"8층",
                 "sch_cd_hight":5001,"sch_nm_hight":"대성고등학교",
                 "agree_ad":"Y","promo_ad":"Y","std_grade":"N"}""".formatted(token);
        String json = call("/dlab/setStdInfo", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return json.replaceAll(".*\"rsv_cd\":\"([^\"]+)\".*", "$1");
    }

    // ── 인증 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 토큰이 최상위에 실린다 — data 배열이 아니다(규격서 3.1)")
    void tokenIsAtTopLevel() throws Exception {
        call("/auth2/token", """
                {"client_id":"dsisa1","secret_id":"%s","service":"dlab"}"""
                .formatted(secretOf("test-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    @DisplayName("★★ 키오스크 토큰으로는 /dlab을 부를 수 없다 — 저장소가 갈라져 있다")
    void kioskTokenCannotAccessHomepage() throws Exception {
        BranchConfig config = new BranchConfig(bundang.getId());
        config.issueKioskCredential("kiosk-client", "kiosk-secret");
        em.persist(config);
        em.flush();

        String kioskToken = dsaTokenService
                .issue("31", "kiosk-client", secretOf("kiosk-secret")).token();

        call("/dlab/getSubInfo", """
                {"token":"%s"}""".formatted(kioskToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(910));   // 토큰 만료·미상
    }

    @Test
    @DisplayName("자격증명이 틀리면 발급되지 않는다")
    void wrongSecretIsRejected() throws Exception {
        call("/auth2/token", """
                {"client_id":"dsisa1","secret_id":"deadbeef","service":"dlab"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(910));
    }

    // ── 저장·조회 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 입학예약이 저장되고 rsv_cd가 최상위로 돌아온다")
    void saveReturnsRsvCd() throws Exception {
        String rsvCd = saveOne();

        assertThat(rsvCd).isNotBlank();
        assertThat(reservationRepository.findByRsvCdAndDeletedFalse(rsvCd)).isPresent();
    }

    @Test
    @DisplayName("★★ 입학예약은 학생으로 등록되지 않는다 — 재원생 통계·키오스크 동기화에 섞이면 안 된다")
    void reservationIsNotAStudent() throws Exception {
        saveOne();

        Long students = em.createQuery(
                        "SELECT COUNT(e) FROM StudentEnrollment e", Long.class).getSingleResult();
        assertThat(students).isZero();
    }

    @Test
    @DisplayName("★★ 학원코드는 알파벳이다 — 키오스크 acad_cd(31)를 넣으면 거부된다")
    void academyCodeIsAlphabet() throws Exception {
        call("/dlab/setStdInfo", """
                {"token":"%s","acid":"31","reg_yyyy":"2026","rsv_nm":"홍길동",
                 "std_tel":"01012345678","par_tel":"01098765432","std_grade":"N"}"""
                .formatted(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(901));
    }

    @Test
    @DisplayName("조회는 배열이다 — 같은 사람이 여러 번 신청할 수 있다")
    void searchReturnsArray() throws Exception {
        saveOne();
        saveOne();
        em.clear();

        call("/dlab/getStdInfo", """
                {"token":"%s","acid":"F","pre_test":1229,"rsv_nm":"홍길동",
                 "birth":"20081106","std_tel":"01012345678"}""".formatted(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].gender_gb_nm").value("남"));
    }

    @Test
    @DisplayName("★★ 연락처를 숫자로 보내도 앞의 0이 살아난다 — 규격서가 std_tel을 Int로 적어놨다")
    void phoneKeepsLeadingZero() throws Exception {
        // 홈페이지가 JSON 숫자로 보내면 01012345678 → 1012345678 로 들어온다
        call("/dlab/setStdInfo", """
                {"token":"%s","acid":"F","reg_yyyy":"2026","rsv_nm":"김숫자",
                 "std_tel":1012345678,"par_tel":1098765432,"gender_gb":"M",
                 "birth":"20081106","std_grade":"N"}""".formatted(token))
                .andExpect(jsonPath("$.code").value(0));
        em.flush();
        em.clear();

        String tel = em.createQuery("""
                SELECT r.studentTel FROM AdmissionReservation r WHERE r.studentName = '김숫자'
                """, String.class).getSingleResult();

        assertThat(tel).isEqualTo("01012345678");
    }

    @Test
    @DisplayName("★ 숫자로 저장된 건도 조회로 찾힌다 — 저장·조회가 같은 규칙을 타야 왕복이 된다")
    void searchFindsNumericPhone() throws Exception {
        call("/dlab/setStdInfo", """
                {"token":"%s","acid":"F","reg_yyyy":"2026","rsv_nm":"김숫자",
                 "std_tel":1012345678,"par_tel":1098765432,"gender_gb":"M",
                 "birth":"20081106","std_grade":"N"}""".formatted(token));
        em.flush();
        em.clear();

        call("/dlab/getStdInfo", """
                {"token":"%s","acid":"F","rsv_nm":"김숫자","birth":"20081106",
                 "std_tel":1012345678}""".formatted(token))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ── 성적·파일 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 같은 과목·구분을 다시 보내면 덮어쓴다 — 홈페이지에서 수정 후 재전송한다")
    void scoreIsOverwritten() throws Exception {
        String rsvCd = saveOne();

        call("/dlab/setStdTest", """
                {"token":"%s","rsv_cd":"%s","score_type":"D","subject":1,"score":"2"}"""
                .formatted(token, rsvCd)).andExpect(jsonPath("$.code").value(0));
        call("/dlab/setStdTest", """
                {"token":"%s","rsv_cd":"%s","score_type":"D","subject":1,"score":"1"}"""
                .formatted(token, rsvCd)).andExpect(jsonPath("$.code").value(0));
        em.flush();

        Long rows = em.createQuery("""
                SELECT COUNT(s) FROM AdmissionScore s WHERE s.reservation.rsvCd = :cd
                """, Long.class).setParameter("cd", rsvCd).getSingleResult();

        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 성적표 본문은 DB에 안 들어간다 — 저장 위치만 남는다")
    void fileBodyIsNotStoredInDb() throws Exception {
        String rsvCd = saveOne();
        String png = Base64.getEncoder().encodeToString("fake-image-bytes".getBytes());

        call("/dlab/setStdFile", """
                {"token":"%s","rsv_cd":"%s","file":"data:image/png;base64,%s"}"""
                .formatted(token, rsvCd, png))
                .andExpect(jsonPath("$.code").value(0));
        em.flush();

        Object[] row = (Object[]) em.createQuery("""
                SELECT f.contentType, f.byteSize, f.storageKey FROM AdmissionFile f
                WHERE f.reservation.rsvCd = :cd
                """).setParameter("cd", rsvCd).getSingleResult();

        assertThat(row[0]).isEqualTo("image/png");
        assertThat(row[1]).isEqualTo(16L);
        assertThat(row[2]).asString().startsWith("admission/" + rsvCd);
    }

    // ── 코드 조회 ─────────────────────────────────────────────

    @Test
    @DisplayName("과목은 gm_cd·gm_nm·idx로 나간다(규격서 3.6)")
    void subjectRowShape() throws Exception {
        call("/dlab/getSubInfo", """
                {"token":"%s"}""".formatted(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].gm_cd").value("10"))
                .andExpect(jsonPath("$.data[0].gm_nm").value("화법과작문"))
                .andExpect(jsonPath("$.data[0].idx").value("1"));
    }

    @Test
    @DisplayName("★ 공통코드는 comm_cd·comm_nm·att1로 나간다(규격서 3.5) — 이름이 다르면 홈페이지가 못 읽는다")
    void commonCodeRowShape() throws Exception {
        call("/dlab/getComInfo", """
                {"token":"%s","acid":"F","grp3":"EXAM"}""".formatted(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].comm_cd").value("1229"))
                .andExpect(jsonPath("$.data[0].comm_nm").value("윈터스쿨"))
                .andExpect(jsonPath("$.data[0].att1").value("2026"));
    }

    @Test
    @DisplayName("★ 성적표 업로드 실패는 103이다(규격서 3.8) — 901과 구분된다")
    void fileFailureUsesOwnCode() throws Exception {
        String rsvCd = saveOne();

        call("/dlab/setStdFile", """
                {"token":"%s","rsv_cd":"%s","file":"data:image/png;base64,!!!not-base64!!!"}"""
                .formatted(token, rsvCd))
                .andExpect(jsonPath("$.code").value(103));
    }
}
