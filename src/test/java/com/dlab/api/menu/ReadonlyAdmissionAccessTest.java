package com.dlab.api.menu;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 조회 전용 계정이 입학예약을 <b>보기만</b> 하는가 (2026-10-27 연구소 요청).
 *
 * <h2>★★ 메뉴를 열어주는 것만으로는 권한이 생기지 않는다</h2>
 * 「조회 전용 계정을 만들고 대기자 관리 메뉴만 체크하면 되지 않나」가 자연스러운 기대인데,
 * <b>메뉴 설정은 「무엇을 숨길지」만 정한다.</b> 역할에 없으면 <b>사이드바에는 보이고
 * 누르면 403</b> 이다 — 빈 화면도 아니고 오류 화면이라 담당자는 설정이 틀린 줄 안다.
 * 그래서 조회(GET)만 {@code READONLY} 에 열었다.
 *
 * <h2>★ 쓰기가 함께 열리지 않는 것이 이 테스트의 요점이다</h2>
 * 클래스 레벨 제약을 풀어 버리면 조회 전용 계정이 <b>상담 상태를 바꾸고 메모를 쓴다.</b>
 * 메서드 레벨로만 열었는지를 여기서 고정한다 — 「조회 전용」이라는 이름이 지켜지는 근거다.
 */
@SpringBootTest
@Transactional
class ReadonlyAdmissionAccessTest {

    private static final String PASSWORD = "viewer-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long viewerId;
    Long otherAcademyId;
    Long otherReservationId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(academy);
        Employee viewer = new Employee(academy, "조회쌤");
        em.persist(viewer);
        Account account = Account.forEmployee(viewer, "ROVIEW",
                passwordEncoder.encode(PASSWORD), false);
        em.persist(account);
        em.flush();
        viewerId = account.getId();
        Academy other = new Academy("32", "일산", LocalTime.of(8, 20));
        em.persist(other);
        em.flush();
        otherAcademyId = other.getId();
        insertReservation(academy.getId(), "READ-OWN");
        otherReservationId = insertReservation(other.getId(), "READ-OTHER");

        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'READONLY'
                        """)
                .setParameter("accountId", account.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("★ 조회 전용 계정이 입학예약 목록·중복찾기를 조회한다")
    void readonlyCanReadAdmissionReservations() throws Exception {
        String token = login();

        mvc.perform(get("/api/v1/admin/admission-reservations")
                        .header("Authorization", token)
                        .param("year", "2026"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/admission-reservations/duplicates")
                        .header("Authorization", token)
                        .param("year", "2026")
                        .param("name", "홍길동"))
                .andExpect(status().isOk());
    }

    /**
     * ★ 상세·상태이력·메모는 <b>없는 건이라 404</b> 가 맞다. 여기서 보려는 것은
     * <b>403 이 아니라는 것</b>이다 — 권한에서 막히면 건이 있든 없든 403 이 온다.
     */
    @Test
    @DisplayName("★ 상세·상태이력·메모도 권한에서 막히지 않는다 (없는 건이라 404)")
    void readonlyPassesPermissionOnDetailPaths() throws Exception {
        String token = login();

        for (String path : new String[] {
                "/api/v1/admin/admission-reservations/999999",
                "/api/v1/admin/admission-reservations/999999/status-logs",
                "/api/v1/admin/admission-reservations/999999/memos" }) {
            mvc.perform(get(path).header("Authorization", token))
                    .andExpect(result -> {
                        int sc = result.getResponse().getStatus();
                        if (sc == 403) {
                            throw new AssertionError(path + " 가 403 이다 — 조회가 열려 있지 않다");
                        }
                    });
        }
    }

    @Test
    @DisplayName("★★ 그래도 쓰기는 막힌다 — 클래스 레벨 제약을 풀지 않았다는 근거")
    void readonlyStillCannotWrite() throws Exception {
        setAllAdmissionRead(true);
        String token = login();

        // 상담 상태 변경 — 풀리면 조회 전용이 「입학확정」을 누를 수 있게 된다
        mvc.perform(post("/api/v1/admin/admission-reservations/1/status")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CALL_NEEDED\"}"))
                .andExpect(status().isForbidden());

        // 메모 작성 — 조회는 열렸지만 쓰기는 아니다
        mvc.perform(post("/api/v1/admin/admission-reservations/1/memos")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"쓰기가 열렸는지 확인\"}"))
                .andExpect(status().isForbidden());

        // 정식 접수 전환 — 학번이 채번되는 경로다
        mvc.perform(post("/api/v1/admin/admission-reservations/1/convert")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/admission-reservations/" + otherReservationId + "/share")
                        .header("Authorization", token))
                .andExpect(status().isForbidden());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/admin/admission-reservations/memos/1")
                        .header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("기본 조회 전용은 소속 지점만 조회하며 다른 지점 상세·중복찾기는 막힌다")
    void defaultScopeStaysLocal() throws Exception {
        String token = login();
        list(token, 1);
        assertOtherDetailPaths(token, false);
        duplicates(token, false);
    }

    @Test
    @DisplayName("DB 허용 계정만 전 지점 예약을 조회하며 같은 토큰으로 허용·해제가 반영된다")
    void crossAcademyReadsFollowDatabaseFlag() throws Exception {
        String token = login();
        list(token, 1);
        setAllAdmissionRead(true);
        list(token, 2);
        assertOtherDetailPaths(token, true);
        duplicates(token, true);

        // 로그인 ID가 바뀌어도 권한은 계정에 붙어 있고, 다른 업무의 지점 범위는 늘지 않는다.
        em.createNativeQuery("UPDATE account SET login_id = 'RENAMED' WHERE id = :id")
                .setParameter("id", viewerId).executeUpdate();
        em.clear();
        list(token, 2);
        var principal = jwtProvider.parse(token.substring(7));
        org.assertj.core.api.Assertions.assertThat(principal.allAcademy()).isFalse();
        org.assertj.core.api.Assertions.assertThat(principal.canAccessAcademy(otherAcademyId)).isFalse();

        setAllAdmissionRead(false);
        list(token, 1);
        assertOtherDetailPaths(token, false);
        duplicates(token, false);
    }

    @Test
    @DisplayName("허용값이 있어도 다른 역할을 가진 계정의 조회 범위는 넓어지지 않는다")
    void flagDoesNotExpandStaffRole() throws Exception {
        setAllAdmissionRead(true);
        em.createNativeQuery("""
                UPDATE account_role SET role_id = (SELECT id FROM role WHERE name = 'STAFF')
                WHERE account_id = :id
                """).setParameter("id", viewerId).executeUpdate();
        em.clear();
        String token = login();
        list(token, 1);
        assertOtherDetailPaths(token, false);
    }

    @Autowired com.dlab.common.security.JwtProvider jwtProvider;

    private void list(String token, int count) throws Exception {
        mvc.perform(get("/api/v1/admin/admission-reservations")
                        .header("Authorization", token).param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(count))
                .andExpect(jsonPath("$.data[*].academyName").value(
                        count == 2 ? org.hamcrest.Matchers.containsInAnyOrder("분당", "일산")
                                : org.hamcrest.Matchers.contains("분당")));
    }

    private void assertOtherDetailPaths(String token, boolean allowed) throws Exception {
        for (String suffix : new String[] { "", "/memos", "/status-logs" }) {
            var result = mvc.perform(get("/api/v1/admin/admission-reservations/" + otherReservationId + suffix)
                            .header("Authorization", token))
                    .andExpect(allowed ? status().isOk() : status().isForbidden());
            if (allowed && suffix.isEmpty()) {
                result.andExpect(jsonPath("$.data.academyName").value("일산"));
            }
        }
    }

    private void duplicates(String token, boolean allowed) throws Exception {
        var result = mvc.perform(get("/api/v1/admin/admission-reservations/duplicates")
                .header("Authorization", token).param("academyId", otherAcademyId.toString())
                .param("year", "2026").param("name", "조회검증").param("birth", "20080101"))
                .andExpect(allowed ? status().isOk() : status().isForbidden());
        if (allowed) result.andExpect(jsonPath("$.data.length()").value(1));
    }

    private void setAllAdmissionRead(boolean allowed) {
        em.createNativeQuery("UPDATE account SET all_admission_read = :allowed WHERE id = :id")
                .setParameter("allowed", allowed).setParameter("id", viewerId).executeUpdate();
        em.clear();
    }

    private Long insertReservation(Long academyId, String code) {
        return ((Number) em.createNativeQuery("""
                INSERT INTO admission_reservation
                    (academy_id, year, rsv_cd, student_name, student_tel, parent_tel, birth, std_grade)
                VALUES (:academyId, 2026, :code, '조회검증', '01000000000', '01000000001', '20080101', 'N')
                RETURNING id
                """).setParameter("academyId", academyId).setParameter("code", code)
                .getSingleResult()).longValue();
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"ROVIEW","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer "
                + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }
}
