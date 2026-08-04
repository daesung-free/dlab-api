package com.dlab.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import com.dlab.domain.user.repository.StudentSearchCondition;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 통합 검색 (F-4.1-1).
 *
 * <p><b>지점 격리가 이 테스트의 핵심이다.</b> 검색은 개인정보를 다루는 첫 화면이라
 * 스코프가 새면 다른 지점 학생 명단이 그대로 노출된다.
 *
 * <p>실행 전 로컬 PostgreSQL이 떠 있어야 한다(src/test/resources/application.yml 참고).
 */
@SpringBootTest
@Transactional
class StudentQueryServiceTest {

    @Autowired
    private StudentQueryService studentQueryService;

    @Autowired
    private EntityManager em;

    private Academy bundang;
    private Academy ilsan;
    private StudentEnrollment minji;

    private AuthPrincipal bundangAdmin;
    private AuthPrincipal headquarters;

    @BeforeEach
    void setUp() {
        bundang = persistAcademy("31", "분당");
        ilsan = persistAcademy("32", "일산");

        minji = persistStudent(bundang, "DL-2026-0419", "김민지", "010-1111-2222",
                "2026-0001", "ABC001", GradeType.HIGH3, TrackType.SCIENCE);
        persistStudent(bundang, "DL-2026-0420", "박서준", "010-3333-4444",
                "2026-0002", null, GradeType.N_SU, TrackType.HUMANITIES);
        persistStudent(ilsan, "DL-2026-0500", "최유나", "010-5555-6666",
                "2026-0100", "XYZ001", GradeType.HIGH3, TrackType.SCIENCE);
        em.flush();

        bundangAdmin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        headquarters = AuthPrincipal.of(2L, "EMPLOYEE", null,
                List.of(Role.SUPER_ADMIN), true);
    }

    private Academy persistAcademy(String code, String name) {
        Academy academy = new Academy(code, name, LocalTime.of(9, 0));
        em.persist(academy);
        return academy;
    }

    private StudentEnrollment persistStudent(Academy academy, String uniqueCode, String name,
                                             String phone, String studentNo, String rfid,
                                             GradeType grade, TrackType track) {
        Student student = new Student(uniqueCode, name, phone);
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, studentNo, rfid, grade);
        // 계열 setter가 없다 — 수정은 Phase 2 담당이라 아직 안 열려 있다.
        // 조회 테스트를 위해 필드만 직접 채운다.
        ReflectionTestUtils.setField(enrollment, "track", track);
        em.persist(enrollment);
        return enrollment;
    }

    private List<String> namesOf(AuthPrincipal me, StudentSearchCondition condition) {
        return studentQueryService.search(me, condition, PageRequest.of(0, 20))
                .getContent().stream()
                .map(e -> e.getStudent().getName())
                .toList();
    }

    @Test
    @DisplayName("★ 지점 관리자는 자기 지점 학생만 본다")
    void branchAdminSeesOwnAcademyOnly() {
        assertThat(namesOf(bundangAdmin, StudentSearchCondition.empty()))
                .containsExactlyInAnyOrder("김민지", "박서준")
                .doesNotContain("최유나");
    }

    @Test
    @DisplayName("★ 본사는 전 지점 학생을 본다")
    void headquartersSeesAllAcademies() {
        assertThat(namesOf(headquarters, StudentSearchCondition.empty()))
                .contains("김민지", "박서준", "최유나");
    }

    @Test
    @DisplayName("통합 검색 — 이름·학번·전화번호를 한 번에 본다")
    void keywordSearchesNameStudentNoAndPhone() {
        assertThat(namesOf(bundangAdmin, condition("김민지"))).containsExactly("김민지");
        assertThat(namesOf(bundangAdmin, condition("2026-0002"))).containsExactly("박서준");
        assertThat(namesOf(bundangAdmin, condition("3333"))).containsExactly("박서준");
    }

    @Test
    @DisplayName("★ 통합 검색도 지점을 넘지 못한다 — 다른 지점 학생 이름을 쳐도 안 나온다")
    void keywordDoesNotEscapeScope() {
        assertThat(namesOf(bundangAdmin, condition("최유나"))).isEmpty();
    }

    @Test
    @DisplayName("조건이 없으면 그 조건은 적용되지 않는다")
    void nullConditionsAreIgnored() {
        int all = namesOf(bundangAdmin, StudentSearchCondition.empty()).size();
        assertThat(all).isEqualTo(2);
    }

    @Test
    @DisplayName("학년·계열로 좁힌다")
    void filtersByGradeAndTrack() {
        var byGrade = new StudentSearchCondition(null, null, null, null, null,
                GradeType.N_SU, null, null, null, null, null, null, null, null);
        assertThat(namesOf(bundangAdmin, byGrade)).containsExactly("박서준");

        var byTrack = new StudentSearchCondition(null, null, null, null, null,
                null, TrackType.SCIENCE, null, null, null, null, null, null, null);
        assertThat(namesOf(bundangAdmin, byTrack)).containsExactly("김민지");
    }

    @Test
    @DisplayName("카드 미발급자를 추린다 — 일괄 발급 대상 추출")
    void filtersByRfidIssued() {
        var noRfid = new StudentSearchCondition(null, null, null, null, null, null, null,
                null, null, null, null, null, null, false);
        assertThat(namesOf(bundangAdmin, noRfid)).containsExactly("박서준");

        var hasRfid = new StudentSearchCondition(null, null, null, null, null, null, null,
                null, null, null, null, null, null, true);
        assertThat(namesOf(bundangAdmin, hasRfid)).containsExactly("김민지");
    }

    @Test
    @DisplayName("재원상태는 복수 선택된다 — 목록 필터가 전체/재원생/퇴원생 3종이다")
    void filtersByMultipleStatuses() {
        var enrolled = new StudentSearchCondition(null, null, null, null, null, null, null,
                List.of(EnrollmentStatus.ENROLLED), null, null, null, null, null, null);
        assertThat(namesOf(bundangAdmin, enrolled)).hasSize(2);

        var withdrawn = new StudentSearchCondition(null, null, null, null, null, null, null,
                List.of(EnrollmentStatus.WITHDRAWN), null, null, null, null, null, null);
        assertThat(namesOf(bundangAdmin, withdrawn)).isEmpty();
    }

    @Test
    @DisplayName("허용되지 않은 정렬 필드는 무시된다 — 임의 컬럼 정렬로 인덱스를 못 타는 걸 막는다")
    void unknownSortFieldIsIgnored() {
        var page = studentQueryService.search(bundangAdmin, StudentSearchCondition.empty(),
                PageRequest.of(0, 20, Sort.by("존재하지않는필드")));

        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    @DisplayName("★ 단건 조회도 지점을 확인한다 — id를 직접 넣어도 남의 지점은 못 본다")
    void singleFetchChecksAcademy() {
        AuthPrincipal ilsanAdmin = AuthPrincipal.of(3L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThat(studentQueryService.getEnrollment(bundangAdmin, minji.getId()).getId())
                .isEqualTo(minji.getId());

        assertThatThrownBy(() -> studentQueryService.getEnrollment(ilsanAdmin, minji.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("★ Export는 검색 조건에 맞는 전건 — 화면 페이지가 아니다")
    void exportContainsAllMatchingRows() {
        byte[] file = studentQueryService.export(bundangAdmin, StudentSearchCondition.empty());

        assertThat(file).isNotEmpty();
        // 헤더 1행 + 분당 학생 2행. 일산 학생은 스코프에서 빠진다.
        assertThat(rowCountOf(file)).isEqualTo(3);
    }

    private int rowCountOf(byte[] xlsx) {
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(xlsx))) {
            return wb.getSheetAt(0).getLastRowNum() + 1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private StudentSearchCondition condition(String keyword) {
        return new StudentSearchCondition(keyword, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
