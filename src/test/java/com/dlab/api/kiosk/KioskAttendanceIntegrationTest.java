package com.dlab.api.kiosk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 키오스크 출결 (DSA 3.14 · 3.22).
 *
 * <p><b>여기서 검증하는 핵심은 "코드가 어느 자리에 실리는가"다.</b>
 * 키오스크는 최상위 {@code code}와 {@code data} 내부 {@code code}를 <b>다르게 분기</b>한다.
 * 자리가 바뀌면 에러 없이 로컬 판별로 폴백해 <b>조용히 틀린 출결</b>이 남는다.
 */
@SpringBootTest
@Transactional
class KioskAttendanceIntegrationTest {

    private static final String CLIENT_ID = "kiosk-att-client";
    private static final String SECRET = "kiosk-att-secret";
    /**
     * 태깅 대상 날짜 = 오늘.
     *
     * <p><b>고정 날짜를 쓰면 안 된다</b> — {@code setReAttendProc}는 파라미터로 날짜를 받지 않고
     * 서버의 오늘을 본다(키오스크가 안 보낸다). 태깅만 미래 날짜로 찍으면 둘이 어긋난다.
     * 대신 아래에서 <b>오늘의 요일 구분</b>에 교시를 심어 요일에 상관없이 돌게 한다.
     */
    private LocalDate today;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired com.dlab.domain.kiosk.service.DsaTokenService tokenService;
    @Autowired AttendanceTaggingLogRepository taggingLogRepository;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    String token;
    Academy bundang;
    StudentEnrollment minji;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        // 등원 기준 09:00 — 이후 태깅은 지각이다
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

        // 오늘의 요일 구분에 교시를 심는다(08:00~22:00). 주말에 CI가 돌아도 동작한다
        today = LocalDate.now(clock);
        DayType dayType = DayType.of(today);
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 0, "0교시",
                dayType, PeriodType.SELF_STUDY, LocalTime.of(8, 0), LocalTime.of(12, 0)));
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 1, "야자",
                dayType, PeriodType.SELF_STUDY, LocalTime.of(13, 0), LocalTime.of(22, 0)));

        em.flush();
        em.clear();
        bundang = em.find(Academy.class, bundang.getId());
        minji = em.find(StudentEnrollment.class, minji.getId());

        token = tokenService.issue("31", CLIENT_ID, md5Secret()).token();
    }

    private String md5Secret() throws Exception {
        String raw = LocalDate.now(clock)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + SECRET;
        byte[] digest = java.security.MessageDigest.getInstance("MD5")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** {@code con_gn} 없이 태깅. */
    private ResultActions tag(String time) throws Exception {
        return tag(time, null);
    }

    private ResultActions tag(String time, String conGn) throws Exception {
        String body = "{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\""
                + ",\"tag_dt\":\"" + today + " " + time + "\""
                + (conGn == null ? "" : ",\"con_gn\":\"" + conGn + "\"")
                + "}";
        return mvc.perform(post("/kiosk/setAttendStd")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private long logCount() {
        em.flush();
        return taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), today)
                .size();
    }

    // ── 정상 판정 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 등원 — att_gn·hak_no·std_nm이 전부 최상위다 (data 안이 아니다)")
    void checkInPutsEverythingAtTopLevel() throws Exception {
        tag("08:30:00")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.att_gn").value("S"))
                .andExpect(jsonPath("$.hak_no").value("2026-0001"))
                // 태깅한 본인만 보는 화면이라 이름은 마스킹하지 않는다(규격서 샘플 "홍길동")
                .andExpect(jsonPath("$.std_nm").value("김민지"))
                .andExpect(jsonPath("$.data.att_gn").doesNotExist());

        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("기준 시각 이후 첫 태깅은 지각(A)")
    void lateAfterDeadline() throws Exception {
        tag("09:30:00").andExpect(jsonPath("$.att_gn").value("A"));
    }

    @Test
    @DisplayName("마지막 교시 종료 후 태깅은 하원(T)")
    void checkOutAfterLastPeriod() throws Exception {
        tag("08:30:00");
        tag("22:00:00").andExpect(jsonPath("$.att_gn").value("T"));
    }

    // ── 분기 코드의 자리 ───────────────────────────────────────

    @Test
    @DisplayName("★ 분기 코드는 data 내부에 실리고 최상위는 0이다 (뒤바뀌면 키오스크가 분기를 놓친다)")
    void branchCodeGoesInsideDataWhileTopLevelStaysZero() throws Exception {
        submitApprovedReason(AbsenceReasonType.EARLY_LEAVE);
        tag("08:30:00");

        tag("15:00:00")
                .andExpect(jsonPath("$.code").value(0))          // ← 최상위는 성공
                .andExpect(jsonPath("$.data[0].code").value(128)) // ← 거부는 안쪽
                .andExpect(jsonPath("$.att_gn").doesNotExist())
                .andExpect(jsonPath("$.hak_no").value("2026-0001"));

        // 되물은 것이므로 원장에 남지 않는다
        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 사유신청이 없으면 130 — 승인 없이 나갈 수 없다")
    void leavingWithoutApprovalIsRejected() throws Exception {
        tag("08:30:00");

        tag("15:00:00")
                .andExpect(jsonPath("$.code").value(130))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("승인 내역이 없습니다")));

        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 121은 최상위에 실린다 (data 내부면 키오스크가 조퇴 차단을 못 본다)")
    void code121GoesToTopLevel() throws Exception {
        submitApprovedReason(AbsenceReasonType.EARLY_LEAVE);
        tag("08:30:00");
        tag("15:00:00", "C").andExpect(jsonPath("$.att_gn").value("C"));

        tag("16:00:00")
                .andExpect(jsonPath("$.code").value(121))
                .andExpect(jsonPath("$.std_nm").value("김민지"));
    }

    @Test
    @DisplayName("운영시간 밖은 122 — data 내부")
    void code122GoesInsideData() throws Exception {
        tag("06:00:00")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value(122));
    }

    @Test
    @DisplayName("★ 승인된 사유신청이 있으면 선택지 코드가 뜬다 — 자동으로 조퇴시키지 않는다")
    void approvedReasonBecomesPromptNotAutoAction() throws Exception {
        submitApprovedReason(AbsenceReasonType.EARLY_LEAVE);
        tag("08:30:00");

        tag("15:00:00")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value(128));   // 조퇴만 가능

        assertThat(logCount()).isEqualTo(1);
    }

    // ── 2-phase 왕복 ─────────────────────────────────────────

    @Test
    @DisplayName("★ 선택지 → con_gn 실어 재호출하면 처리된다 (서버는 사이 상태를 안 든다)")
    void statelessTwoPhaseRoundTrip() throws Exception {
        submitApprovedReason(AbsenceReasonType.EARLY_LEAVE);
        tag("08:30:00");
        tag("15:00:00").andExpect(jsonPath("$.data[0].code").value(128));

        tag("15:00:30", "C").andExpect(jsonPath("$.att_gn").value("C"));

        assertThat(logCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 승인 없는 사유조퇴(C)는 130 — 없으면 키오스크에서 아무나 눌러 조퇴가 된다")
    void excusedActionsRequireApproval() throws Exception {
        tag("08:30:00");

        tag("15:00:00", "C")
                .andExpect(jsonPath("$.code").value(130))
                .andExpect(jsonPath("$.message").value("승인된 조퇴 신청이 없습니다."));

        tag("15:00:00", "N")
                .andExpect(jsonPath("$.code").value(130));

        // 학생이 명시적으로 고른 외출(D)은 승인 없이도 받는다 — 113 왕복의 2차 호출이다
        tag("15:00:00", "D").andExpect(jsonPath("$.att_gn").value("D"));
    }

    // ── 중복 억제 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 1분 내 재태깅은 원장에 안 남고 직전 결과를 그대로 돌려준다 (규격서 3.14)")
    void duplicateWithinOneMinuteReplaysFirstResult() throws Exception {
        tag("08:30:00").andExpect(jsonPath("$.att_gn").value("S"));

        tag("08:30:20").andExpect(jsonPath("$.att_gn").value("S"));
        tag("08:30:59").andExpect(jsonPath("$.att_gn").value("S"));

        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 학생이 고른 요청(con_gn)은 중복억제 창을 타지 않는다 — 2-phase 2차가 삼켜진다")
    void explicitActionBypassesDedupWindow() throws Exception {
        tag("08:30:00").andExpect(jsonPath("$.att_gn").value("S"));

        // 8초 뒤 2차 호출. 창 안이지만 학생이 화면에서 고른 것이라 처리돼야 한다
        tag("08:30:08", "D").andExpect(jsonPath("$.att_gn").value("D"));

        assertThat(logCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 외출 중에는 중복억제 창을 적용하지 않는다 — 키오스크가 복귀(R)를 기대한다")
    void dedupWindowDoesNotBlockReturn() throws Exception {
        tag("08:30:00");
        tag("15:00:00", "D").andExpect(jsonPath("$.att_gn").value("D"));

        // 25초 만에 복귀 — 창 안이지만 R로 처리돼야 한다.
        // 직전 결과(D)를 되돌려주면 키오스크가 "상태 불일치"로 에러를 띄운다
        tag("15:00:25").andExpect(jsonPath("$.att_gn").value("R"));

        assertThat(logCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("1분이 지나면 다시 판정한다")
    void afterDedupWindowItDecidesAgain() throws Exception {
        tag("08:30:00").andExpect(jsonPath("$.att_gn").value("S"));
        tag("08:31:01").andExpect(jsonPath("$.code").value(130));
    }

    // ── 조퇴 해제 ────────────────────────────────────────────

    @Test
    @DisplayName("★ setReAttendProc — 조퇴 기록을 지우지 않고 외출로 정정한 뒤 복귀를 덧붙인다")
    void reAttendCorrectsInsteadOfDeleting() throws Exception {
        submitApprovedReason(AbsenceReasonType.EARLY_LEAVE);
        tag("08:30:00");
        tag("15:00:00", "C").andExpect(jsonPath("$.att_gn").value("C"));

        mvc.perform(post("/kiosk/setReAttendProc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        em.flush();
        var logs = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), today);

        // 조퇴 행이 사라지지 않고 외출로 바뀌어야 한다 — 지우면 정정 이력이 끊긴다.
        //
        // 순서는 단언하지 않는다: 태깅은 tag_dt(15:00)로 들어가는데 복귀는 서버 현재 시각이라
        // 테스트를 15시 이전에 돌리면 복귀가 앞에 온다. 실제 키오스크는 항상 현재 시각을
        // 보내므로 운영에서는 생기지 않는 어긋남이다.
        assertThat(logs).extracting(l -> l.getEventType())
                .containsExactlyInAnyOrder(AttendanceEventType.CHECK_IN,
                        AttendanceEventType.OUTING,
                        AttendanceEventType.RETURN);
        assertThat(logs).noneMatch(l -> l.getEventType() == AttendanceEventType.EARLY_LEAVE);
    }

    @Test
    @DisplayName("조퇴 상태가 아닌데 해제 요청하면 거부")
    void reAttendWithoutEarlyLeaveIsRejected() throws Exception {
        tag("08:30:00");

        mvc.perform(post("/kiosk/setReAttendProc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));
    }

    // ── 방어 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ 다른 지점 카드는 101 — HTTP는 200이다")
    void unknownCardIsRejectedWithHttp200() throws Exception {
        mvc.perform(post("/kiosk/setAttendStd")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"NOPE\","
                                + "\"tag_dt\":\"" + today + " 08:30:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));
    }

    @Test
    @DisplayName("★ 깨진 tag_dt는 901 — 서버 시각으로 덮으면 지각 판정이 뒤집힌다")
    void malformedTagDtIsParameterError() throws Exception {
        mvc.perform(post("/kiosk/setAttendStd")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\","
                                + "\"tag_dt\":\"2026/08/05 08:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_PARAMETER.value()));
    }

    /**
     * 승인까지 끝난 사유신청을 만든다.
     *
     * <p>승인 상태는 리포지토리의 조건부 UPDATE로만 바뀌므로(동시성 처리) 테스트에서는
     * 필드를 직접 채운다 — 승인 흐름 자체는 {@code FirewallApprovalFlowTest}가 검증한다.
     */
    private void submitApprovedReason(AbsenceReasonType type) {
        ApprovalItem item = new ApprovalItem(bundang, (short) 2026,
                RequestType.ABSENCE_REASON, ApproverType.TEACHER, null, null);
        em.persist(item);

        ApprovalRequest approval = new ApprovalRequest(
                bundang, item, minji, null, Instant.now(clock));
        ReflectionTestUtils.setField(approval, "status", ApprovalStatus.APPROVED);
        em.persist(approval);

        AbsenceReason reason = new AbsenceReason(bundang, minji, today, type, "사유");
        reason.linkApproval(approval);
        em.persist(reason);
        em.flush();
    }
}
