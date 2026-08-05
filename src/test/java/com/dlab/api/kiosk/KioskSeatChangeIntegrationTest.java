package com.dlab.api.kiosk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 키오스크 좌석 변경 (DSA 3.23). 키오스크 계약 중 몇 안 되는 쓰기다. */
@SpringBootTest
@Transactional
class KioskSeatChangeIntegrationTest {

    private static final String CLIENT_ID = "kiosk-seat-client";
    private static final String SECRET = "kiosk-seat-secret";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired com.dlab.domain.kiosk.service.DsaTokenService tokenService;
    @Autowired SeatAssignmentRepository seatAssignmentRepository;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    String token;
    Academy bundang;
    StudentEnrollment minji;
    StudentEnrollment seojun;
    SeatMaster seatA1;
    SeatMaster seatA2;
    SeatMaster aisle;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        BranchConfig config = new BranchConfig(bundang.getId());
        config.issueKioskCredential(CLIENT_ID, SECRET);
        em.persist(config);

        minji = enroll("DL-2026-0419", "김민지", "2026-0001", "ABC001");
        seojun = enroll("DL-2026-0420", "박서준", "2026-0002", "ABC002");

        StudyArea area = new StudyArea(bundang, "A", "A구역", (short) 1);
        em.persist(area);
        seatA1 = new SeatMaster(bundang, area, "A-01", "1번", 1, 1);
        em.persist(seatA1);
        seatA2 = new SeatMaster(bundang, area, "A-02", "2번", 2, 1);
        em.persist(seatA2);

        // 통로 — 좌석표에는 보이지만 앉을 수 없다
        aisle = new SeatMaster(bundang, area, "A-99", "통로", 3, 1);
        ReflectionTestUtils.setField(aisle, "usable", false);
        em.persist(aisle);

        em.flush();
        em.clear();
        bundang = em.find(Academy.class, bundang.getId());
        minji = em.find(StudentEnrollment.class, minji.getId());
        seojun = em.find(StudentEnrollment.class, seojun.getId());
        seatA1 = em.find(SeatMaster.class, seatA1.getId());
        seatA2 = em.find(SeatMaster.class, seatA2.getId());
        aisle = em.find(SeatMaster.class, aisle.getId());

        token = tokenService.issue("31", CLIENT_ID, md5Secret()).token();
    }

    private StudentEnrollment enroll(String code, String name, String stdNo, String rfid) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, bundang, (short) 2026, stdNo, rfid, GradeType.HIGH3);
        em.persist(enrollment);
        return enrollment;
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

    private ResultActions change(String rfid, String seatCd) throws Exception {
        return mvc.perform(post("/kiosk/setSeatChgProc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"rfid_no\":\"" + rfid
                                + "\",\"seat_cd\":\"" + seatCd + "\"}"))
                .andExpect(status().isOk());
    }

    private String currentSeatOf(StudentEnrollment enrollment) {
        return seatAssignmentRepository.findActiveByEnrollmentId(enrollment.getId())
                .map(a -> a.getSeat().getSeatCd())
                .orElse(null);
    }

    @Test
    @DisplayName("미배정 학생에게 좌석을 준다. 응답은 code·message만이다")
    void assignsSeatToUnassignedStudent() throws Exception {
        change("ABC001", "A-01")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.att_gn").doesNotExist());

        assertThat(currentSeatOf(minji)).isEqualTo("A-01");
    }

    @Test
    @DisplayName("★ 자리를 옮기면 이전 배정은 해제되고 이력으로 남는다")
    void movingReleasesPreviousAssignmentButKeepsHistory() throws Exception {
        change("ABC001", "A-01");
        change("ABC001", "A-02").andExpect(jsonPath("$.code").value(0));

        assertThat(currentSeatOf(minji)).isEqualTo("A-02");

        // 지우지 않는다 — "그 시각에 누가 어느 좌석이었나"를 출결과 대조해야 한다
        assertThat(seatAssignmentRepository.findAll())
                .filteredOn(a -> a.getEnrollment().getId().equals(minji.getId()))
                .hasSize(2);
    }

    @Test
    @DisplayName("★ 남의 자리는 뺏지 않는다 — 앉아 있던 학생이 통보 없이 자리를 잃으면 안 된다")
    void doesNotEvictAnotherStudent() throws Exception {
        change("ABC001", "A-01");

        change("ABC002", "A-01")
                .andExpect(jsonPath("$.code").value(DsaCode.SEAT_OCCUPIED.value()));

        assertThat(currentSeatOf(minji)).isEqualTo("A-01");
        assertThat(currentSeatOf(seojun)).isNull();
    }

    @Test
    @DisplayName("★ 이미 그 자리면 성공이다 — 재시도가 영원히 실패로 남으면 안 된다")
    void sameSeatIsIdempotent() throws Exception {
        change("ABC001", "A-01");
        change("ABC001", "A-01").andExpect(jsonPath("$.code").value(0));
        change("ABC001", "A-01").andExpect(jsonPath("$.code").value(0));

        assertThat(seatAssignmentRepository.findAll())
                .filteredOn(a -> a.getEnrollment().getId().equals(minji.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 통로에는 배정할 수 없다")
    void cannotAssignToAisle() throws Exception {
        change("ABC001", "A-99")
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));

        assertThat(currentSeatOf(minji)).isNull();
    }

    @Test
    @DisplayName("없는 좌석코드는 101")
    void unknownSeatCodeIsRejected() throws Exception {
        change("ABC001", "Z-99")
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));
    }

    @Test
    @DisplayName("★ 다른 지점 카드는 101 — 지점 확인 없이 처리하면 안 된다")
    void otherAcademyCardIsRejected() throws Exception {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        Student other = new Student("DL-2026-0500", "최유나", "010-5555-6666");
        em.persist(other);
        em.persist(new StudentEnrollment(other, ilsan, (short) 2026,
                "2026-0100", "XYZ999", GradeType.HIGH3));
        em.flush();

        change("XYZ999", "A-01")
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_KEY.value()));
    }

    @Test
    @DisplayName("seat_cd가 비면 901")
    void blankSeatCodeIsParameterError() throws Exception {
        change("ABC001", "")
                .andExpect(jsonPath("$.code").value(DsaCode.INVALID_PARAMETER.value()));
    }
}
