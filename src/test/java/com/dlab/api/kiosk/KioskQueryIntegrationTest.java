package com.dlab.api.kiosk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 키오스크 DSA 호환 조회 9종.
 *
 * <p><b>검증 기준은 키오스크 백엔드의 파싱 코드다</b>({@code doc/kiosk/deasung-kiosk-backend}).
 * 그래서 여기 단언은 "우리가 보기 좋은 형태"가 아니라 <b>그쪽이 실제로 읽는 키</b>를 본다:
 * <ul>
 *   <li>성공 판정은 {@code code == 0} — HTTP 상태가 아니다</li>
 *   <li>{@code data}는 <b>평면 배열</b>이어야 한다. 이중 배열이면 그쪽
 *       {@code getDataAsList()}가 <b>빈 리스트를 돌려준다</b>(조용히 0건이 된다)</li>
 *   <li>키는 snake_case 원본 표기</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class KioskQueryIntegrationTest {

    private static final String CLIENT_ID = "kiosk-it-client";
    private static final String SECRET = "kiosk-it-secret";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired com.dlab.domain.kiosk.service.DsaTokenService tokenService;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    String token;
    Academy bundang;
    StudentEnrollment minji;
    SeatMaster seatA1;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

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

        ParentGuardian father = new ParentGuardian("김아빠", "010-9999-8888", "M");
        em.persist(father);
        em.persist(new StudentGuardianLink(student, father, (short) 1));

        StudyArea area = new StudyArea(bundang, "A", "A구역", (short) 1);
        em.persist(area);
        seatA1 = new SeatMaster(bundang, area, "A-01", "1번", 10, 20);
        em.persist(seatA1);
        em.persist(new SeatAssignment(bundang, seatA1, minji));

        em.flush();
        em.clear();

        bundang = em.find(Academy.class, bundang.getId());
        minji = em.find(StudentEnrollment.class, minji.getId());
        seatA1 = em.find(SeatMaster.class, seatA1.getId());

        token = tokenService.issue("31", CLIENT_ID, md5Secret()).token();
    }

    /** {@code secret_id = MD5(yyyyMMdd + secret)}. 실제 키오스크가 만드는 값과 같은 방식. */
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

    private org.springframework.test.web.servlet.ResultActions call(String path, String body)
            throws Exception {
        return mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String tokenOnly() {
        return "{\"token\":\"" + token + "\"}";
    }

    // ── 학생·지점·학부모 ──────────────────────────────────────────────

    @Test
    @DisplayName("★ getStdInfoList — 평면 배열 + 이름 마스킹 + hp는 뒷 4자리 (공용 화면이다)")
    void studentListIsFlatArray() throws Exception {
        call("/kiosk/getStdInfoList", tokenOnly())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                // 첫 원소가 배열이 아니라 객체여야 한다
                .andExpect(jsonPath("$.data[0].std_no").value("2026-0001"))
                // 규격서 샘플: 이름은 "홍*동", hp는 뒷 4자리 "1521"
                .andExpect(jsonPath("$.data[0].std_nm").value("김*지"))
                .andExpect(jsonPath("$.data[0].hp").value("2222"))
                .andExpect(jsonPath("$.data[0].rfid_no").value("ABC001"))
                .andExpect(jsonPath("$.data[0].seat_cd").value("A-01"));
    }

    @Test
    @DisplayName("★ getStdInfo — 상세는 전체 번호다 (본인 한 명만 나오는 화면). std_no 필수")
    void studentDetailReturnsFullPhoneAndStudentNo() throws Exception {
        call("/kiosk/getStdInfo",
                "{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\"}")
                .andExpect(jsonPath("$.code").value(0))
                // 키오스크가 std_no로 한 번 더 필터한다 — 빠지면 전화번호가 null이 된다
                .andExpect(jsonPath("$.data[0].std_no").value("2026-0001"))
                .andExpect(jsonPath("$.data[0].std_nm").value("김민지"))
                .andExpect(jsonPath("$.data[0].hp").value("010-1111-2222"))
                // 규격서엔 없지만 키오스크가 hp ?? p_hp로 폴백하므로 함께 싣는다
                .andExpect(jsonPath("$.data[0].p_hp").value("010-9999-8888"))
                // 주소는 우리 스키마에 없다. 키만 유지한다
                .andExpect(jsonPath("$.data[0].zip").doesNotExist())
                .andExpect(jsonPath("$.data[0].addr1").doesNotExist());
    }

    @Test
    @DisplayName("★ 다른 지점 카드로는 조회되지 않는다")
    void rfidIsScopedToAcademy() throws Exception {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        Student other = new Student("DL-2026-0500", "최유나", "010-5555-6666");
        em.persist(other);
        em.persist(new StudentEnrollment(other, ilsan, (short) 2026,
                "2026-0100", "XYZ999", GradeType.HIGH3));
        em.flush();

        // 분당 토큰으로 일산 학생 카드 조회 → 실패해야 한다
        call("/kiosk/getStdInfo",
                "{\"token\":\"" + token + "\",\"rfid_no\":\"XYZ999\"}")
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));
    }

    @Test
    @DisplayName("getDlabList — 요청 지점이 아니라 전 지점을 내린다")
    void academyListReturnsAllBranches() throws Exception {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        call("/kiosk/getDlabList", tokenOnly())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.acad_cd=='31')].acad_nm").value("분당"))
                .andExpect(jsonPath("$.data[?(@.acad_cd=='32')].acad_nm").value("일산"));
    }

    @Test
    @DisplayName("★ getParentHpList — p_gb는 성별이 아니라 관계다 (남=부=F). 그대로 넘기면 아빠가 '모'로 뜬다")
    void parentGenderIsMappedToRelationCode() throws Exception {
        call("/kiosk/getParentHpList",
                "{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\"}")
                .andExpect(jsonPath("$.code").value(0))
                // gender 'M'(남)으로 저장했지만 p_gb는 'F'(Father)로 나가야 한다
                .andExpect(jsonPath("$.data[0].p_gb").value("F"))
                // 부모에게 전화를 걸어야 하는 화면이라 전체 번호다
                .andExpect(jsonPath("$.data[0].p_hp").value("010-9999-8888"));
    }

    @Test
    @DisplayName("성별 미상 보호자는 기타(E) — 규격서 3값 중 하나여야 한다")
    void unknownGenderBecomesOtherRelation() throws Exception {
        ParentGuardian unknown = new ParentGuardian("보호자", "010-7777-6666", null);
        em.persist(unknown);
        em.persist(new StudentGuardianLink(
                em.find(com.dlab.domain.user.entity.Student.class,
                        minji.getStudent().getId()), unknown, (short) 2));
        em.flush();

        call("/kiosk/getParentHpList",
                "{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\"}")
                .andExpect(jsonPath("$.data[?(@.p_hp=='010-7777-6666')].p_gb").value("E"));
    }

    @Test
    @DisplayName("getRequestListStd — 사유신청이 없으면 빈 배열이지 null이 아니다")
    void requestListReturnsEmptyArrayNotNull() throws Exception {
        call("/kiosk/getRequestListStd",
                "{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\",\"month\":\"2026-08\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                // 규격서는 hak_no·std_nm을 최상위에도 싣는다
                .andExpect(jsonPath("$.hak_no").value("2026-0001"))
                .andExpect(jsonPath("$.std_nm").value("김*지"));
    }

    @Test
    @DisplayName("★ 잘못된 month는 규격서 코드 102")
    void invalidMonthReturnsCode102() throws Exception {
        call("/kiosk/getRequestListStd",
                "{\"token\":\"" + token + "\",\"rfid_no\":\"ABC001\",\"month\":\"2026년8월\"}")
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_MONTH.value()));
    }

    // ── 좌석 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getStudyAreaInfo — 구역 목록")
    void areaList() throws Exception {
        call("/kiosk/getStudyAreaInfo", tokenOnly())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].area_cd").value("A"))
                .andExpect(jsonPath("$.data[0].area_nm").value("A구역"))
                // 규격서 area_inwon = 구역 총 수용인원. 좌석 수에서 센다
                .andExpect(jsonPath("$.data[0].area_inwon").value("1"));
    }

    @Test
    @DisplayName("★ getStudyAreaSeatInfo — 좌표 키는 규격서 표기 x_pos/y_pos (배치도가 이걸로 그려진다)")
    void seatInfoUsesSpecCoordinateKeys() throws Exception {
        call("/kiosk/getStudyAreaSeatInfo",
                "{\"token\":\"" + token + "\",\"area_cd\":\"A\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].seat_cd").value("A-01"))
                .andExpect(jsonPath("$.data[0].seat_nm").value("1번"))
                .andExpect(jsonPath("$.data[0].x_pos").value("10"))
                .andExpect(jsonPath("$.data[0].y_pos").value("20"))
                .andExpect(jsonPath("$.data[0].seat_gn").value("Y"));
    }

    @Test
    @DisplayName("★ 좌석 상태 4종 — 배정됐는데 안 왔으면 N(미출석)이지 B(공석)가 아니다")
    void seatStateDerivesFromLatestTagging() throws Exception {
        String path = "/kiosk/getStudyAreaSeatState";
        String body = "{\"token\":\"" + token + "\",\"area_cd\":\"A\"}";

        // 배정은 됐는데 아직 태깅 전 → 미출석. 공석(B)과 구분돼야
        // 사감이 "결석자 자리"와 "빈 자리"를 구별할 수 있다
        call(path, body).andExpect(jsonPath("$.data[0].state").value("N"));

        tag(AttendanceEventType.CHECK_IN, Instant.now().minusSeconds(600));
        call(path, body).andExpect(jsonPath("$.data[0].state").value("S"));

        tag(AttendanceEventType.OUTING, Instant.now().minusSeconds(60));
        call(path, body).andExpect(jsonPath("$.data[0].state").value("D"));

        // 하원한 자리는 공석이 아니라 "오늘 더는 안 오는 자리"다
        tag(AttendanceEventType.CHECK_OUT, Instant.now());
        call(path, body).andExpect(jsonPath("$.data[0].state").value("N"));
    }

    @Test
    @DisplayName("★ 아무도 배정되지 않은 좌석만 B(공석)다")
    void unassignedSeatIsEmpty() throws Exception {
        StudyArea area = em.find(SeatMaster.class, seatA1.getId()).getStudyArea();
        em.persist(new SeatMaster(bundang, area, "A-02", "2번", 11, 20));
        em.flush();

        call("/kiosk/getStudyAreaSeatState",
                "{\"token\":\"" + token + "\",\"area_cd\":\"A\"}")
                .andExpect(jsonPath("$.data[?(@.seat_cd=='A-01')].state").value("N"))
                .andExpect(jsonPath("$.data[?(@.seat_cd=='A-02')].state").value("B"));
    }

    private void tag(AttendanceEventType type, Instant at) {
        em.persist(new AttendanceTaggingLog(bundang, minji, type,
                AttendanceSource.KIOSK_NFC, at, LocalDate.now(clock)));
        em.flush();
    }

    @Test
    @DisplayName("없는 구역을 물으면 빈 배열 — 에러가 아니다 (키오스크에 에러 화면을 띄우지 않는다)")
    void unknownAreaReturnsEmpty() throws Exception {
        call("/kiosk/getStudyAreaSeatInfo",
                "{\"token\":\"" + token + "\",\"area_cd\":\"ZZZ\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ── 상벌점 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("★ getPointStdList — 벌점은 음수로 내린다 (키오스크가 카테고리를 안 받는다)")
    void demeritIsNegative() throws Exception {
        PenaltyItem demerit = new PenaltyItem(bundang, (short) 2026, "지각", 5, PenaltyCategory.DEMERIT);
        em.persist(demerit);
        PenaltyItem merit = new PenaltyItem(bundang, (short) 2026, "모범", 3, PenaltyCategory.MERIT);
        em.persist(merit);

        em.persist(new PenaltyPoint(bundang, minji, demerit, demerit.getPointValue(),
                "지각 3회", PenaltySource.MANUAL, null));
        em.persist(new PenaltyPoint(bundang, minji, merit, merit.getPointValue(),
                "청소 도움", PenaltySource.MANUAL, null));
        em.flush();

        LocalDate today = LocalDate.now(clock);
        call("/kiosk/getPointStdList",
                "{\"token\":\"" + token + "\",\"st_dt\":\"" + today.withDayOfMonth(1)
                        + "\",\"ed_dt\":\"" + today + "\"}")
                .andExpect(jsonPath("$.code").value(0))
                // 규격서 샘플이 "point" : "-1" — 문자열이고 벌점은 음수다
                .andExpect(jsonPath("$.data[?(@.reason=='지각 3회')].point").value("-5"))
                .andExpect(jsonPath("$.data[?(@.reason=='청소 도움')].point").value("3"))
                .andExpect(jsonPath("$.data[0].std_no").value("2026-0001"))
                .andExpect(jsonPath("$.data[0].std_nm").value("김*지"));
    }

    // ── 인증 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 토큰이 없거나 틀리면 910 — HTTP는 200이다 (4xx면 키오스크가 파싱 전에 예외를 던진다)")
    void invalidTokenReturnsCode910WithHttp200() throws Exception {
        call("/kiosk/getStdInfoList", "{\"token\":\"없는토큰\"}")
                .andExpect(jsonPath("$.code").value(DsaCode.TOKEN_EXPIRED.value()));

        call("/kiosk/getStdInfoList", "{}")
                .andExpect(jsonPath("$.code").value(DsaCode.TOKEN_EXPIRED.value()));
    }
}
