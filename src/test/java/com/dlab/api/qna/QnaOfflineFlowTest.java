package com.dlab.api.qna;

import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
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

import java.time.LocalDate;
import java.time.LocalTime;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 질의응답 대면 예약 (F-4.11-7 OFF · A-13).
 *
 * <p>지키려는 것 — <b>간격이 바뀌어도 스키마가 안 바뀔 것</b>, <b>정원이 지켜질 것</b>,
 * <b>학생에게 남의 예약이 안 보일 것</b>, <b>마감이 삭제가 아닐 것</b>.
 */
@SpringBootTest
@Transactional
class QnaOfflineFlowTest {

    private static final String PASSWORD = "qna-password-1234";
    private static final short YEAR = 2026;
    /** 넉넉히 미래로 둔다 — "지난 타임" 검사에 걸리면 안 된다. */
    private static final LocalDate DAY = LocalDate.of(2027, 3, 2);

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    String studentA = "010-5000-0001";
    String studentB = "010-5000-0002";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("QN01", "질의응답테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        academyId = academy.getId();

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "QNADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId()).executeUpdate();

        createStudent(academy, "QNSTU001", "학생A", studentA);
        createStudent(academy, "QNSTU002", "학생B", studentB);
        em.flush();
    }

    private void createStudent(Academy academy, String code, String name, String phone) {
        Student student = new Student(code, name, phone);
        em.persist(student);
        em.persist(new StudentEnrollment(student, academy, YEAR,
                "2026-" + code.substring(code.length() - 4), null, GradeType.N_SU));
        Account account = Account.forStudent(student, phone, passwordEncoder.encode(PASSWORD));
        account.approve();
        em.persist(account);
    }

    private String token(String loginId, String path) throws Exception {
        String body = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private String adminToken() throws Exception {
        return token("QNADM", "/api/v1/admin/auth/login");
    }

    private String appToken(String phone) throws Exception {
        return token(phone, "/api/v1/app/auth/login");
    }

    /** 슬롯 개설. 기본 14:00~15:00, 15분 간격 → 4개. */
    private String openSlots(String from, String to, int interval, Integer capacity)
            throws Exception {
        String body = mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"%s","from":"%s","to":"%s",
                                 "intervalMinutes":%d,"room":"상담실1"%s}"""
                                .formatted(academyId, YEAR, DAY, from, to, interval,
                                        capacity == null ? "" : ",\"capacity\":" + capacity)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return body;
    }

    private long firstSlotId(String openBody) {
        return objectMapper.readTree(openBody).path("data").get(0).path("id").asLong();
    }

    private long reserve(String phone, long slotId) throws Exception {
        String body = mvc.perform(post("/api/v1/app/qna/offline/slots/{id}/reservations", slotId)
                        .header("Authorization", appToken(phone))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"미적분 질문이요"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    // ── 개설 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 간격을 받아 슬롯을 여러 개 만든다 — 간격이 바뀌어도 스키마는 그대로다")
    void openSlotsByInterval() throws Exception {
        // 14:00~15:00, 15분 → 4개
        mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"%s","from":"14:00","to":"15:00",
                                 "intervalMinutes":15,"room":"상담실1"}"""
                                .formatted(academyId, YEAR, DAY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].startTime").value("14:00:00"))
                .andExpect(jsonPath("$.data[0].endTime").value("14:15:00"))
                .andExpect(jsonPath("$.data[3].startTime").value("14:45:00"));
    }

    @Test
    @DisplayName("간격을 30분으로 바꿔도 그냥 동작한다")
    void differentInterval() throws Exception {
        mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"%s","from":"14:00","to":"15:00",
                                 "intervalMinutes":30,"room":"상담실2"}"""
                                .formatted(academyId, YEAR, DAY)))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("★ 이미 있는 시각은 건너뛴다 — 오전 열어두고 오후를 추가하는 흐름이 있다")
    void skipsExistingSlots() throws Exception {
        openSlots("14:00", "15:00", 15, null);   // 4개

        // 14:30~15:30 → 14:30·14:45는 이미 있고 15:00·15:15만 새로 생긴다
        mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"%s","from":"14:30","to":"15:30",
                                 "intervalMinutes":15,"room":"상담실1"}"""
                                .formatted(academyId, YEAR, DAY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("시작이 종료보다 늦으면 거부한다")
    void invalidTimeRange() throws Exception {
        mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"%s","from":"15:00","to":"14:00",
                                 "intervalMinutes":15}""".formatted(academyId, YEAR, DAY)))
                .andExpect(status().isBadRequest());
    }

    // ── 예약 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("예약하면 인원이 올라가고 정원이 차면 마감된다")
    void reserveAndFull() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 1));
        reserve(studentA, slotId);

        mvc.perform(get("/api/v1/app/qna/offline/slots")
                        .header("Authorization", appToken(studentB))
                        .param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reserved").value(1))
                .andExpect(jsonPath("$.data[0].full").value(true));
    }

    @Test
    @DisplayName("★ 정원이 차면 거절한다 — 상담 시간은 겹칠 수 없어 대기 개념이 없다")
    void reserveRejectedWhenFull() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 1));
        reserve(studentA, slotId);

        mvc.perform(post("/api/v1/app/qna/offline/slots/{id}/reservations", slotId)
                        .header("Authorization", appToken(studentB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QNA_SLOT_FULL"));
    }

    @Test
    @DisplayName("★ 학생에게는 남의 예약이 안 보인다 — 인원 수만 준다")
    void studentSeesCountNotNames() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 2));
        reserve(studentA, slotId);

        mvc.perform(get("/api/v1/app/qna/offline/slots")
                        .header("Authorization", appToken(studentB))
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data[0].reserved").value(1))
                // 명단은 비어 있어야 한다
                .andExpect(jsonPath("$.data[0].reservations").isEmpty());
    }

    @Test
    @DisplayName("관리자는 예약자 명단을 본다")
    void adminSeesReservations() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 2));
        reserve(studentA, slotId);

        mvc.perform(get("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data[0].reservations.length()").value(1))
                .andExpect(jsonPath("$.data[0].reservations[0].studentName").value("학생A"))
                .andExpect(jsonPath("$.data[0].reservations[0].question").value("미적분 질문이요"));
    }

    @Test
    @DisplayName("같은 타임을 두 번 예약할 수 없다")
    void cannotReserveTwice() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 5));
        reserve(studentA, slotId);

        mvc.perform(post("/api/v1/app/qna/offline/slots/{id}/reservations", slotId)
                        .header("Authorization", appToken(studentA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QNA_ALREADY_RESERVED"));
    }

    @Test
    @DisplayName("★ 취소하면 다시 예약할 수 있다 — 유니크가 취소분까지 막으면 영영 못 들어온다")
    void canReserveAfterCancel() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 1));
        long reservationId = reserve(studentA, slotId);

        mvc.perform(delete("/api/v1/app/qna/offline/reservations/{id}", reservationId)
                .header("Authorization", appToken(studentA))).andExpect(status().isOk());
        em.flush();

        // 자리가 났으니 B도 들어갈 수 있고, A도 다시 잡을 수 있다
        reserve(studentB, slotId);
    }

    @Test
    @DisplayName("★ 남의 예약은 취소할 수 없다")
    void cannotCancelOthers() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 5));
        long reservationId = reserve(studentA, slotId);

        mvc.perform(delete("/api/v1/app/qna/offline/reservations/{id}", reservationId)
                        .header("Authorization", appToken(studentB)))
                .andExpect(status().isForbidden());
    }

    // ── 마감 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 마감하면 새 예약만 막히고 기존 예약은 유지된다 — 삭제가 아니다")
    void closingKeepsExistingReservations() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 5));
        reserve(studentA, slotId);

        mvc.perform(put("/api/v1/admin/qna/offline/slots/{id}/closed", slotId)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"closed":true}""")).andExpect(status().isOk());
        em.flush();

        // 새 예약은 막힌다
        mvc.perform(post("/api/v1/app/qna/offline/slots/{id}/reservations", slotId)
                        .header("Authorization", appToken(studentB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("QNA_SLOT_CLOSED"));

        // 기존 예약은 그대로 남아 있다
        mvc.perform(get("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .param("academyId", String.valueOf(academyId))
                        .param("date", DAY.toString()))
                .andExpect(jsonPath("$.data[0].reservations.length()").value(1));
    }

    // ── 지난 타임 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ 지난 타임은 예약할 수 없다")
    void cannotReservePastSlot() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"2020-01-06","from":"14:00",
                                 "to":"15:00","intervalMinutes":60}"""
                                .formatted(academyId, YEAR)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();

        mvc.perform(post("/api/v1/app/qna/offline/slots/{id}/reservations", firstSlotId(body))
                        .header("Authorization", appToken(studentA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("QNA_SLOT_PAST"));
    }

    // ── 내 예약 ──────────────────────────────────────────────────

    @Test
    @DisplayName("내 예약 내역에는 취소분도 이력으로 나온다")
    void myReservationsIncludeCanceled() throws Exception {
        long slotId = firstSlotId(openSlots("14:00", "15:00", 15, 5));
        long reservationId = reserve(studentA, slotId);
        mvc.perform(delete("/api/v1/app/qna/offline/reservations/{id}", reservationId)
                .header("Authorization", appToken(studentA))).andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/app/qna/offline/reservations")
                        .header("Authorization", appToken(studentA))
                        .param("from", DAY.minusDays(1).toString())
                        .param("to", DAY.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].canceledAt").exists());
    }

    // ── 하루 상한 · 직원 대리 예약 (디랩 1029 8-1) ─────────────────

    /**
     * ★★ 하루 상한을 넘으면 거절한다.
     *
     * <p>★ 상한은 <b>지점 설정</b>({@code academy.qna_daily_limit}, 기본 2)이고
     * ⚠️ <b>코드에 2를 박지 않았다</b>(1029 지침).
     */
    @Test
    @DisplayName("★★ 하루 상한(기본 2)을 넘으면 거절한다 — 지점 설정값이다")
    void dailyLimitRejectsThird() throws Exception {
        String body = openSlots("14:00", "16:00", 15, 1);
        var slots = objectMapper.readTree(body).path("data");

        reserve(studentA, slots.get(0).path("id").asLong());
        reserve(studentA, slots.get(1).path("id").asLong());

        // ★ 세 번째 — 같은 날짜라 거절된다
        mvc.perform(post("/api/v1/app/qna/offline/slots/{id}/reservations",
                        slots.get(2).path("id").asLong())
                        .header("Authorization", appToken(studentA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"세 번째\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QNA_DAILY_LIMIT_EXCEEDED"));
    }

    /**
     * ★★ <b>취소한 자리는 상한에서 빠진다.</b>
     *
     * <p>⚠️ 취소분을 세면 <b>취소하고 다시 잡을 수 없다</b> — 자리는 비어 있는데
     * 학생은 그날 더 못 잡는다.
     */
    @Test
    @DisplayName("★★ 취소하면 상한에서 빠진다 — 세면 취소하고 다시 잡을 수 없다")
    void canceledDoesNotCountToLimit() throws Exception {
        String body = openSlots("14:00", "16:00", 15, 1);
        var slots = objectMapper.readTree(body).path("data");

        long first = reserve(studentA, slots.get(0).path("id").asLong());
        reserve(studentA, slots.get(1).path("id").asLong());

        mvc.perform(delete("/api/v1/app/qna/offline/reservations/{id}", first)
                        .header("Authorization", appToken(studentA)))
                .andExpect(status().isOk());
        em.flush();

        // ★ 한 자리가 비었으니 다시 잡힌다
        reserve(studentA, slots.get(2).path("id").asLong());
    }

    /** ★ 다른 날짜는 상한과 무관하다 — 「하루」가 슬롯 날짜 기준이다. */
    @Test
    @DisplayName("★ 상한은 날짜별이다 — 다른 날은 다시 2건 잡힌다")
    void limitIsPerDate() throws Exception {
        var sameDay = objectMapper.readTree(openSlots("14:00", "15:00", 15, 1)).path("data");
        reserve(studentA, sameDay.get(0).path("id").asLong());
        reserve(studentA, sameDay.get(1).path("id").asLong());

        // ★ 다음 날 슬롯
        String other = mvc.perform(post("/api/v1/admin/qna/offline/slots")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"date":"%s","from":"14:00","to":"15:00",
                                 "intervalMinutes":15,"room":"상담실2","capacity":1}"""
                                .formatted(academyId, YEAR, DAY.plusDays(1))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.flush();

        reserve(studentA, objectMapper.readTree(other).path("data").get(0).path("id").asLong());
    }

    /**
     * ★★ <b>직원 대리 예약은 상한을 건너뛴다.</b>
     *
     * <p>★ 상한은 <b>학생 셀프 신청을 고르게 나누기</b> 위한 것이고, 대리 예약은
     * <b>직원이 사정을 보고 넣는 것</b>이다(1029 지침).
     *
     * <p>⚠️ 이 경로가 없던 동안은 <b>전화로 신청한 학생을 넣어 줄 수 없었다.</b>
     */
    @Test
    @DisplayName("★★ 직원 대리 예약은 상한을 건너뛴다 — 전화 신청을 넣어 줄 경로다")
    void staffReserveSkipsLimit() throws Exception {
        var slots = objectMapper.readTree(openSlots("14:00", "16:00", 15, 1)).path("data");
        reserve(studentA, slots.get(0).path("id").asLong());
        reserve(studentA, slots.get(1).path("id").asLong());

        long enrollmentId = ((Number) em.createNativeQuery("""
                        select e.id from student_enrollment e
                          join student s on s.id = e.student_id
                         where s.unique_code = 'QNSTU001'
                        """).getSingleResult()).longValue();

        mvc.perform(post("/api/v1/admin/qna/offline/slots/{id}/reservations",
                        slots.get(2).path("id").asLong())
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentId\":%d,\"question\":\"전화 신청\"}"
                                .formatted(enrollmentId)))
                .andExpect(status().isOk());
    }

    /**
     * ★★ 대리 예약도 <b>같은 슬롯 중복은 막는다.</b>
     *
     * <p>⚠️ 상한만 건너뛴다 — <b>한 자리에 두 번 앉을 수는 없다.</b>
     */
    @Test
    @DisplayName("★★ 대리 예약도 같은 슬롯 중복은 막는다 — 한 자리에 두 번 앉을 수 없다")
    void staffReserveStillBlocksDuplicate() throws Exception {
        var slots = objectMapper.readTree(openSlots("14:00", "15:00", 15, 2)).path("data");
        long slotId = slots.get(0).path("id").asLong();
        reserve(studentA, slotId);

        long enrollmentId = ((Number) em.createNativeQuery("""
                        select e.id from student_enrollment e
                          join student s on s.id = e.student_id
                         where s.unique_code = 'QNSTU001'
                        """).getSingleResult()).longValue();

        mvc.perform(post("/api/v1/admin/qna/offline/slots/{id}/reservations", slotId)
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentId\":%d}".formatted(enrollmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QNA_ALREADY_RESERVED"));
    }
}
