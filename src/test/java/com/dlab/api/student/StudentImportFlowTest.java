package com.dlab.api.student;

import com.dlab.common.privacy.Masking;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 학생 엑셀 일괄 업로드 (F-4.1-2 · P1-02).
 *
 * <p>가장 중요한 건 <b>마스킹 왕복</b>이다 — 내려받은 파일을 그대로 다시 올렸을 때
 * 연락처가 날아가지 않아야 한다.
 */
@SpringBootTest
@Transactional
class StudentImportFlowTest {

    private static final String PASSWORD = "import-test-password-1234";
    private static final short YEAR = 2026;
    private static final String REAL_PHONE = "010-1234-5678";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;
    String existingCode;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("IM01", "업로드테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account account = Account.forEmployee(admin, "IMADM", passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", account.getId()).executeUpdate();

        // 이미 있는 학생 — 연락처가 실제 값으로 들어있다
        Student student = new Student("EXIST001", "김기존", REAL_PHONE);
        em.persist(student);
        StudentEnrollment enrollment =
                new StudentEnrollment(student, academy, YEAR, "2026-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        em.flush();

        existingCode = student.getUniqueCode();
        academyId = academy.getId();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"IMADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    /** 헤더와 행을 받아 실제 xlsx 바이트를 만든다. */
    private MockMultipartFile excel(List<String> headers, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("학생");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    if (values.get(c) != null) {
                        row.createCell(c).setCellValue(values.get(c));
                    }
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "students.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private ResultActions upload(String path, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/v1/admin/students" + path)
                .file(file)
                .header("Authorization", token())
                .param("academyId", academyId.toString())
                .param("year", String.valueOf(YEAR)));
    }

    private String phoneOf(String uniqueCode) {
        return em.createQuery("SELECT s.phone FROM Student s WHERE s.uniqueCode = :code", String.class)
                .setParameter("code", uniqueCode).getSingleResult();
    }

    @Test
    @DisplayName("★ 마스킹된 연락처를 다시 올려도 기존 번호가 유지된다 — 이게 깨지면 전 학생 연락처가 날아간다")
    void maskedPhoneKeepsExistingValue() throws Exception {
        // Export한 파일을 그대로 다시 올리는 상황. 연락처는 이미 마스킹돼 있다.
        MockMultipartFile file = excel(
                List.of("학생고유ID", "이름", "연락처", "학년"),
                List.of(List.of(existingCode, "김기존", Masking.phone(REAL_PHONE), "N수생")));

        upload("/import", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.errorRows").value(0))
                .andExpect(jsonPath("$.data.rows[0].existing").value(true));
        em.flush();
        em.clear();

        assertThat(phoneOf(existingCode)).isEqualTo(REAL_PHONE);
    }

    @Test
    @DisplayName("★ 신규 등록에 마스킹 값이 오면 오류다 — 유지할 원래 값이 없다")
    void maskedPhoneOnNewStudentIsError() throws Exception {
        MockMultipartFile file = excel(
                List.of("이름", "연락처", "학년"),
                List.of(List.of("신규생", "010-****-5678", "고3")));

        upload("/import", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validRows").value(0))
                .andExpect(jsonPath("$.data.errorRows").value(1))
                .andExpect(jsonPath("$.data.errors[0].field").value("phone"));
        em.flush();

        Long created = em.createQuery(
                        "SELECT COUNT(s) FROM Student s WHERE s.name = '신규생'", Long.class)
                .getSingleResult();
        assertThat(created).isZero();
    }

    @Test
    @DisplayName("실제 연락처를 올리면 정상적으로 바뀐다 — 마스킹 판정이 과하게 걸리지 않는지 확인")
    void realPhoneStillUpdates() throws Exception {
        MockMultipartFile file = excel(
                List.of("학생고유ID", "이름", "연락처", "학년"),
                List.of(List.of(existingCode, "김기존", "010-9999-8888", "N수생")));

        upload("/import", file).andExpect(status().isOk());
        em.flush();
        em.clear();

        assertThat(phoneOf(existingCode)).isEqualTo("010-9999-8888");
    }

    @Test
    @DisplayName("★ 미리보기는 아무것도 저장하지 않는다")
    void previewWritesNothing() throws Exception {
        MockMultipartFile file = excel(
                List.of("이름", "연락처", "학년"),
                List.of(List.of("미리보기생", "010-1111-2222", "고3")));

        upload("/import/preview", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validRows").value(1));
        em.flush();

        Long created = em.createQuery(
                        "SELECT COUNT(s) FROM Student s WHERE s.name = '미리보기생'", Long.class)
                .getSingleResult();
        assertThat(created).isZero();
    }

    @Test
    @DisplayName("★ 오류행이 있어도 정상행은 반영한다 — 전량 롤백하면 실무가 안 돈다")
    void validRowsApplyDespiteErrors() throws Exception {
        MockMultipartFile file = excel(
                List.of("이름", "학년"),
                List.of(
                        List.of("정상일", "고3"),
                        List.of("오류생", "대학생"),   // 없는 학년
                        List.of("정상이", "N수생")));

        upload("/import", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(3))
                .andExpect(jsonPath("$.data.validRows").value(2))
                .andExpect(jsonPath("$.data.errorRows").value(1));
        em.flush();

        Long created = em.createQuery("""
                SELECT COUNT(s) FROM Student s WHERE s.name IN ('정상일', '정상이', '오류생')
                """, Long.class).getSingleResult();
        assertThat(created).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 열 순서가 바뀌거나 별칭을 써도 읽는다 — 위치가 아니라 헤더명으로 찾는다")
    void flexibleHeaderMapping() throws Exception {
        // 순서를 뒤집고 "이름" 대신 "성명", "연락처" 대신 "휴대폰"
        MockMultipartFile file = excel(
                List.of("학년", "휴대폰", "성명"),
                List.of(List.of("고2", "010-3333-4444", "별칭생")));

        upload("/import", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.rows[0].name").value("별칭생"))
                .andExpect(jsonPath("$.data.rows[0].grade").value("HIGH2"));
    }

    @Test
    @DisplayName("필수 컬럼이 없으면 파일 자체를 거부한다")
    void missingRequiredColumnRejected() throws Exception {
        MockMultipartFile file = excel(List.of("연락처"), List.of(List.of("010-1111-2222")));

        upload("/import", file).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ Export → 그대로 Import 왕복이 성립한다 (마스킹된 연락처가 날아가지 않는다)")
    void exportImportRoundTrip() throws Exception {
        byte[] exported = mvc.perform(get("/api/v1/admin/students/export")
                        .header("Authorization", token())
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // 내려받은 파일의 연락처는 마스킹돼 있다
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(exported))) {
            var sheet = wb.getSheetAt(0);
            int phoneCol = -1;
            for (int i = 0; i < sheet.getRow(0).getLastCellNum(); i++) {
                if ("연락처".equals(sheet.getRow(0).getCell(i).getStringCellValue())) {
                    phoneCol = i;
                }
            }
            assertThat(phoneCol).isNotNegative();
            assertThat(sheet.getRow(1).getCell(phoneCol).getStringCellValue())
                    .isEqualTo("010-****-5678");
        }

        // 손대지 않고 그대로 다시 올린다 — 실무에서 가장 흔한 흐름
        MockMultipartFile again = new MockMultipartFile("file", "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exported);
        upload("/import", again).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorRows").value(0))
                .andExpect(jsonPath("$.data.rows[0].existing").value(true));
        em.flush();
        em.clear();

        // 연락처가 마스킹 문자열로 덮이지 않았다
        assertThat(phoneOf(existingCode)).isEqualTo(REAL_PHONE);
    }

    @Test
    @DisplayName("★ 고유ID가 같으면 새 학생을 만들지 않고 기존 학생을 갱신한다 — 상담 이력이 끊기면 안 된다")
    void existingStudentIsUpdatedNotDuplicated() throws Exception {
        MockMultipartFile file = excel(
                List.of("학생고유ID", "이름", "학년", "계열"),
                List.of(List.of(existingCode, "김기존", "N수생", "자연")));

        upload("/import", file).andExpect(status().isOk());
        em.flush();
        em.clear();

        Long people = em.createQuery(
                        "SELECT COUNT(s) FROM Student s WHERE s.uniqueCode = :code", Long.class)
                .setParameter("code", existingCode).getSingleResult();
        assertThat(people).isEqualTo(1);

        TrackType track = em.createQuery("""
                SELECT e.track FROM StudentEnrollment e WHERE e.student.uniqueCode = :code
                """, TrackType.class).setParameter("code", existingCode).getSingleResult();
        assertThat(track).isEqualTo(TrackType.SCIENCE);
    }
}
