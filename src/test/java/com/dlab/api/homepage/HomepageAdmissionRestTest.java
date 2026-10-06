package com.dlab.api.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.admission.repository.AdmissionReservationRepository;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 홈페이지 입학예약 B안(REST) 수신.
 *
 * <p>지키려는 것 셋 — <b>키 없이는 못 들어온다</b>, <b>같은 신청을 다시 보내도 한 건이다</b>,
 * <b>실패가 실패로 보인다</b>(A안은 실패해도 200이었다).
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "homepage.integration.api-key=test-homepage-key-1234")
class HomepageAdmissionRestTest {

    private static final String KEY = "Bearer test-homepage-key-1234";

    @Autowired WebApplicationContext context;
    @Autowired AdmissionReservationRepository reservationRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        // 홈페이지 학원코드는 키오스크 acadCd 와 다른 체계다 — 설정 전용 컬럼이라 직접 넣는다
        em.createNativeQuery("UPDATE academy SET dlab_cd = 'F' WHERE id = :id")
                .setParameter("id", bundang.getId()).executeUpdate();
    }

    private String body(String requestId, String name) {
        return """
                {"requestId":"%s","academyCode":"F","year":2027,
                 "name":"%s","studentTel":"010-1111-2222","parentTel":"010-3333-4444","grade":"N",
                 "agreePrivacy":"Y","agreeMarketing":"N"}
                """.formatted(requestId, name);
    }

    @Test
    @DisplayName("접수되면 접수번호가 돌아온다")
    void accepts() throws Exception {
        mvc.perform(post("/api/v1/homepage/admissions")
                        .header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REQ-1", "김지원")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationNo").isNotEmpty());

        assertThat(reservationRepository.findByRequestIdAndDeletedFalse("REQ-1")).isPresent();
    }

    @Test
    @DisplayName("★ 같은 고유번호로 다시 보내도 한 건이다 — 재전송이 중복 접수가 되면 안 된다")
    void sameRequestIdIsOneReservation() throws Exception {
        String first = mvc.perform(post("/api/v1/homepage/admissions")
                        .header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REQ-2", "박서준")))
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/v1/homepage/admissions")
                        .header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REQ-2", "박서준")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String no1 = objectMapper.readTree(first).path("data").path("reservationNo").asString();
        String no2 = objectMapper.readTree(second).path("data").path("reservationNo").asString();

        // 접수번호까지 같아야 한다 — 홈페이지는 둘 다 성공으로 처리한다
        assertThat(no2).isEqualTo(no1);
        assertThat(reservationRepository.findAll().stream()
                .filter(r -> "REQ-2".equals(r.getRequestId()))
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 키가 없거나 틀리면 401이다 — A안과 달리 실패가 실패로 보인다")
    void rejectsWithoutKey() throws Exception {
        mvc.perform(post("/api/v1/homepage/admissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REQ-3", "최유나")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/homepage/admissions")
                        .header("Authorization", "Bearer wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REQ-4", "최유나")))
                .andExpect(status().isUnauthorized());

        assertThat(reservationRepository.findByRequestIdAndDeletedFalse("REQ-3")).isEmpty();
    }

    @Test
    @DisplayName("필수값이 빠지면 400이다 — 200에 code 숫자로 알리지 않는다")
    void rejectsInvalid() throws Exception {
        mvc.perform(post("/api/v1/homepage/admissions")
                        .header("Authorization", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyCode":"F","name":"이름없는요청"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("선택 항목 값 목록을 내려준다 — 아직 비어 있는 것이 정상이다")
    void codes() throws Exception {
        mvc.perform(get("/api/v1/homepage/codes")
                        .header("Authorization", KEY)
                        .param("group", "ACAD")
                        .param("academyCode", "F"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }
}
