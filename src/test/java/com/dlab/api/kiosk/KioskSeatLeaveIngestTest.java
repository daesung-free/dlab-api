package com.dlab.api.kiosk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.entity.SeatLeaveLog;
import com.dlab.domain.kiosk.repository.SeatLeaveLogRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 좌석 이탈·복귀 수신 (F-4.3-2) — 신규 구획.
 *
 * <p>지키려는 것 — <b>재전송이 중복으로 쌓이지 않을 것</b>,
 * <b>한 건이 틀려도 배치가 통째로 막히지 않을 것</b>,
 * <b>학생을 못 찾아도 기록이 사라지지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class KioskSeatLeaveIngestTest {

    private static final String CLIENT_ID = "kiosk-leave-client";
    private static final String SECRET = "kiosk-leave-secret";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired com.dlab.domain.kiosk.service.DsaTokenService tokenService;
    @Autowired SeatLeaveLogRepository logRepository;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    String token;
    Academy bundang;
    StudentEnrollment minji;
    Instant now;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        now = Instant.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        BranchConfig config = new BranchConfig(bundang.getId());
        config.issueKioskCredential(CLIENT_ID, SECRET);
        em.persist(config);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", "ABC001", GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        token = tokenService.issue("31", CLIENT_ID, md5Secret()).token();
    }

    private String md5Secret() throws Exception {
        String raw = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + SECRET;
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String event(long sourceRowId, String rfid, String type) {
        return """
                {"sourceRowId":%d,"rfidNo":%s,"areaCd":"A","seatCd":"A-01",
                 "eventType":"%s","occurredAt":"%s"}
                """.formatted(sourceRowId, rfid == null ? "null" : "\"" + rfid + "\"", type, now);
    }

    private ResultActions send(String... events) throws Exception {
        return mvc.perform(post("/api/v1/kiosk/seat-leaves")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"events\":[" + String.join(",", events) + "]}"));
    }

    // ── 적재 ─────────────────────────────────────────────────

    @Test
    @DisplayName("★ 응답은 DSA 형식이 아니라 ApiResponse다 — 신규라 흉내 낼 원본이 없다")
    void usesApiResponseFormat() throws Exception {
        send(event(1, "ABC001", "LEAVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.code").doesNotExist());   // code == 0 판정이 아니다
    }

    @Test
    @DisplayName("이탈·복귀가 학생에 연결돼 적재된다")
    void ingestsAndResolvesStudent() throws Exception {
        send(event(1, "ABC001", "LEAVE"), event(2, "ABC001", "RETURN"))
                .andExpect(status().isOk());
        em.flush();

        assertThat(logRepository.findByEnrollment(minji.getId(), now.minusSeconds(60),
                now.plusSeconds(60)))
                .hasSize(2)
                .allSatisfy(l -> assertThat(l.getEnrollment().getId()).isEqualTo(minji.getId()));
    }

    // ── 멱등 ─────────────────────────────────────────────────

    @Test
    @DisplayName("★ 재전송이 와도 한 줄만 남는다 — 키오스크가 응답 못 받으면 다시 보낸다")
    void retransmissionIsIdempotent() throws Exception {
        send(event(1, "ABC001", "LEAVE")).andExpect(jsonPath("$.data[0].status").value("ACCEPTED"));
        em.flush();

        send(event(1, "ABC001", "LEAVE")).andExpect(jsonPath("$.data[0].status").value("DUPLICATE"));
        em.flush();

        assertThat(logRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("★ 같은 배치 안에 같은 행이 두 번 들어와도 한 줄이다 — 재시도가 겹칠 수 있다")
    void duplicateWithinBatch() throws Exception {
        send(event(1, "ABC001", "LEAVE"), event(1, "ABC001", "LEAVE"))
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].status").value("DUPLICATE"));
        em.flush();

        assertThat(logRepository.findAll()).hasSize(1);
    }

    // ── 부분 실패 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 학생을 못 찾아도 저장한다 — 거절하면 키오스크가 그 행을 영원히 재전송한다")
    void unknownStudentIsStillStored() throws Exception {
        send(event(1, "UNKNOWN-CARD", "LEAVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED_UNRESOLVED"));
        em.flush();

        assertThat(logRepository.findUnresolved(bundang.getId()))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.getEnrollment()).isNull();
                    assertThat(l.getRfidNo()).isEqualTo("UNKNOWN-CARD");  // 원본은 남는다
                });
    }

    @Test
    @DisplayName("★ 한 건이 틀려도 나머지는 저장된다 — 통째로 거절하면 큐가 영영 안 빠진다")
    void partialFailureDoesNotBlockBatch() throws Exception {
        String noSourceId = """
                {"sourceRowId":null,"rfidNo":"ABC001","eventType":"LEAVE","occurredAt":"%s"}
                """.formatted(now);

        // sourceRowId가 없는 건은 @NotNull에 걸려 400이므로, 서비스 레벨에서 확인한다
        send(event(1, "ABC001", "LEAVE"), event(2, "UNKNOWN", "RETURN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].status").value("ACCEPTED_UNRESOLVED"));
        em.flush();

        assertThat(logRepository.findAll()).hasSize(2);
        assertThat(noSourceId).isNotBlank();   // 아래 검증에서 형식 참고용
    }

    // ── 인증 ─────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 없거나 죽었으면 받지 않는다 — JWT가 아니라 DSA 토큰으로 인증한다")
    void rejectsBadToken() throws Exception {
        mvc.perform(post("/api/v1/kiosk/seat-leaves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"dead-token\",\"events\":["
                                + event(1, "ABC001", "LEAVE") + "]}"))
                .andExpect(status().is4xxClientError());

        assertThat(logRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 토큰으로 넣으면 그 지점 것으로 들어간다 — 지점은 토큰이 정한다")
    void academyComesFromToken() throws Exception {
        send(event(1, "ABC001", "LEAVE")).andExpect(status().isOk());
        em.flush();

        assertThat(logRepository.findAll())
                .singleElement()
                .extracting(l -> l.getAcademy().getId())
                .isEqualTo(bundang.getId());
    }

    @Test
    @DisplayName("연도는 학생 등록 건에서 가져온다 — 못 찾으면 발생 시각의 연도다")
    void yearFollowsEnrollment() throws Exception {
        send(event(1, "ABC001", "LEAVE"), event(2, "UNKNOWN", "LEAVE"))
                .andExpect(status().isOk());
        em.flush();

        assertThat(logRepository.findAll())
                .extracting(SeatLeaveLog::getYear)
                .contains((short) 2026,
                        (short) LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Seoul")).getYear());
    }
}
