package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamPurpose;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import com.dlab.domain.grade.service.ExamFormAdminService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 성적 양식 용도 — 입학 전 성적 / 디랩에서 본 시험.
 *
 * <p>지키려는 것 — <b>둘이 섞이지 않을 것</b>(업로드가 입학 성적을 지우던 원인),
 * <b>월례고사가 달마다 따로 설 것</b>, <b>학생 가입 양식에 디랩 시험이 뜨지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class ExamPurposeTest {

    @Autowired ExamFormAdminService adminService;
    @Autowired ExamMasterRepository examMasterRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2091;

    AuthPrincipal head;

    @BeforeEach
    void setUp() {
        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        head = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN), true, false);
    }

    private ExamFormAdminService.Command command(ExamCode code, ExamPurpose purpose, LocalDate date) {
        return new ExamFormAdminService.Command(null, YEAR, GradeType.N_SU, code,
                code + " " + date, 1,
                List.of(new ExamFormAdminService.SubjectInput("KOREAN", "국어", 1, true, true, true)),
                purpose, date);
    }

    @Test
    @DisplayName("★ 입학용과 디랩용은 같은 6월이어도 따로 선다 — 예전엔 한 행이라 업로드가 입학 성적을 지웠다")
    void admissionAndAcademyCoexist() {
        adminService.create(head, command(ExamCode.JUNE, ExamPurpose.ADMISSION, null));
        adminService.create(head, command(ExamCode.JUNE, ExamPurpose.ACADEMY, LocalDate.of(YEAR, 6, 4)));
        em.flush();

        assertThat(adminService.list(head, YEAR, null)).hasSize(2);
    }

    @Test
    @DisplayName("★ 월례고사는 달마다 따로 선다 — 같은 날짜만 중복이다")
    void monthlyExamsSeparatedByDate() {
        adminService.create(head, command(ExamCode.MONTHLY, ExamPurpose.ACADEMY, LocalDate.of(YEAR, 7, 16)));
        adminService.create(head, command(ExamCode.MONTHLY, ExamPurpose.ACADEMY, LocalDate.of(YEAR, 8, 18)));
        em.flush();

        assertThatThrownBy(() -> adminService.create(head,
                command(ExamCode.MONTHLY, ExamPurpose.ACADEMY, LocalDate.of(YEAR, 8, 18))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("디랩 시험은 시행일이 필수다 — 월례고사가 코드만으로는 구분되지 않는다")
    void academyRequiresDate() {
        assertThatThrownBy(() -> adminService.create(head,
                command(ExamCode.JUNE, ExamPurpose.ACADEMY, null)))
                .hasMessageContaining("시행일");
    }

    @Test
    @DisplayName("월례고사는 입학 전 성적으로 만들 수 없다 — 입학 전 성적에는 더프가 없다")
    void monthlyIsAcademyOnly() {
        assertThatThrownBy(() -> adminService.create(head,
                command(ExamCode.MONTHLY, ExamPurpose.ADMISSION, LocalDate.of(YEAR, 8, 18))))
                .hasMessageContaining("월례고사");
    }

    @Test
    @DisplayName("★ 학생 가입 양식에는 디랩 시험이 뜨지 않는다 — 뜨면 학생이 8월 더프 칸을 채운다")
    void studentFormExcludesAcademy() {
        adminService.create(head, command(ExamCode.JUNE, ExamPurpose.ADMISSION, null));
        adminService.create(head, command(ExamCode.MONTHLY, ExamPurpose.ACADEMY, LocalDate.of(YEAR, 8, 18)));
        em.flush();

        List<ExamMaster> form = examMasterRepository.findForm(YEAR, GradeType.N_SU, 999999L);

        assertThat(form).extracting(ExamMaster::getPurpose).containsOnly(ExamPurpose.ADMISSION);
        assertThat(form).hasSize(1);
    }

    @Test
    @DisplayName("용도를 비우면 입학 전 성적이다 — 기존 호출이 그대로 동작해야 한다")
    void purposeDefaultsToAdmission() {
        ExamMaster exam = adminService.create(head, command(ExamCode.SEPT, null, null));

        assertThat(exam.getPurpose()).isEqualTo(ExamPurpose.ADMISSION);
    }
}
