package com.dlab.api.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.api.admin.profile.AdminStudentProfileController.StudentProfileResponse;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.service.FileStorage;
import com.dlab.domain.profile.repository.StudentProfileRepository;
import com.dlab.domain.profile.service.StudentProfileService;
import com.dlab.domain.profile.service.StudentProfileService.SaveCommand;
import com.dlab.domain.profile.service.StudentProfileService.UniversityCommand;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신상기록부 (F-4.11-9).
 *
 * <p>프론트는 화면을 완성해 뒀는데 <b>저장할 곳이 없었다</b>.
 *
 * <p>지키려는 것 여섯이다.
 * <ol>
 *   <li><b>★★ 사람이 아니라 등록 건에 붙는다</b> — 재등록하면 그해 기록이 섞이면 안 된다</li>
 *   <li><b>★ 등록 건마다 한 장</b> — 두 장이면 상담 현황의 작성 여부가 어느 쪽을 보는지 모른다</li>
 *   <li><b>★ 목표대학은 표 전체를 받는다</b> — 뺀 줄은 soft delete 로 남는다(합격증이 매달려 있다)</li>
 *   <li><b>★★ 합격증은 {@code file_attachment} id 만 참조한다</b> — 새 저장소를 만들지 않는다</li>
 *   <li><b>⚠️ 건강상태는 민감정보라 담임에게는 가려지고 {@code masked} 가 실린다</b></li>
 *   <li><b>★ 남의 지점 학생은 거절한다</b> — 등록 건 id 를 화면이 보낸다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class StudentProfileTest {

    @Autowired StudentProfileService profileService;
    @Autowired StudentProfileRepository profileRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;
    @MockitoBean FileStorage storage;

    Academy academy;
    Academy other;
    StudentEnrollment minji;
    StudentEnrollment stranger;
    AuthPrincipal branchAdmin;
    AuthPrincipal teacher;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now().getYear();

        academy = new Academy("SP01", "신상지점", LocalTime.of(9, 0));
        other = new Academy("SP02", "남의지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.persist(other);

        Teacher t = new Teacher(academy, "박담임", "010-2000-0001");
        em.persist(t);
        Account account = Account.forTeacher(t, "sp-teacher", "x", false);
        em.persist(account);

        minji = enroll(academy, "김민지", "0001");
        stranger = enroll(other, "남의학생", "0002");
        em.flush();

        branchAdmin = AuthPrincipal.of(account.getId(), "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        teacher = AuthPrincipal.of(account.getId(), "TEACHER", academy.getId(),
                List.of(Role.TEACHER), false);
    }

    private StudentEnrollment enroll(Academy at, String name, String studentNo) {
        Student person = new Student("S" + at.getAcadCd() + studentNo, name,
                "010-6000-" + studentNo);
        em.persist(person);
        StudentEnrollment e = new StudentEnrollment(person, at, year, studentNo,
                "R" + at.getAcadCd() + studentNo, GradeType.N_SU);
        em.persist(e);
        return e;
    }

    private FileAttachment certificate() {
        FileAttachment file = new FileAttachment(FileVisibility.PRIVATE,
                "student-profile/2026/합격증.pdf", "합격증.pdf", "application/pdf", 1024L,
                StudentProfileService.CERTIFICATE_OWNER_TYPE, null);
        em.persist(file);
        return file;
    }

    private SaveCommand basic(List<UniversityCommand> universities) {
        return new SaveCommand(null,
                "땅콩 알레르기. 비상시 에피펜 소지", Map.of("allergy", true, "medication", "에피펜"),
                "취침 01시 · 아침 결식", Map.of("sleepHours", 6),
                Map.of("note", "양식 미확정분"),
                universities, null);
    }

    @Test
    @DisplayName("★ 저장·조회가 왕복한다 — 건강·생활습관·목표대학")
    void savesAndReads() {
        profileService.save(branchAdmin, minji.getId(), basic(List.of(
                new UniversityCommand("서울대학교", "경영학과", "지역균형", null, null),
                new UniversityCommand("연세대학교", "경제학부", "논술", null, null))));
        em.flush();
        em.clear();

        var detail = profileService.find(branchAdmin, minji.getId()).orElseThrow();

        assertThat(detail.healthNote()).contains("에피펜");
        assertThat(detail.healthItems()).containsEntry("allergy", true);
        assertThat(detail.lifeItems()).containsEntry("sleepHours", 6);
        assertThat(detail.answers()).containsKey("note");
        // 순서가 보낸 순서대로 유지된다 — 1지망·2지망이 순서로 표현된다
        assertThat(detail.universities())
                .extracting(StudentProfileService.UniversityDetail::universityName)
                .containsExactly("서울대학교", "연세대학교");
    }

    @Test
    @DisplayName("★ 등록 건마다 한 장이다 — 다시 저장하면 같은 장을 고친다")
    void oneSheetPerEnrollment() {
        var first = profileService.save(branchAdmin, minji.getId(), basic(List.of()));
        em.flush();
        var second = profileService.save(branchAdmin, minji.getId(),
                new SaveCommand(null, "고친 건강 메모", null, null, null, null, List.of(), null));
        em.flush();
        em.clear();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(profileRepository.findWrittenEnrollmentIds(academy.getId(), year))
                .containsExactly(minji.getId());

        var detail = profileService.find(branchAdmin, minji.getId()).orElseThrow();
        assertThat(detail.healthNote()).isEqualTo("고친 건강 메모");
        // ★ 보내지 않은 칸은 비워진다 — 「지운 것」과 「안 보낸 것」을 구분할 수 없으면
        //   잘못 적은 건강 메모를 지울 수 없다
        assertThat(detail.healthItems()).isNull();
        assertThat(detail.lifeNote()).isNull();
    }

    @Test
    @DisplayName("★ 목표대학은 표 전체로 교체된다 — 뺀 줄은 사라지고 새 줄이 들어온다")
    void replacesUniversityTable() {
        profileService.save(branchAdmin, minji.getId(), basic(List.of(
                new UniversityCommand("서울대학교", "경영학과", "지역균형", null, null),
                new UniversityCommand("연세대학교", "경제학부", "논술", null, null))));
        em.flush();

        var after = profileService.save(branchAdmin, minji.getId(), basic(List.of(
                new UniversityCommand("고려대학교", "통계학과", "학업우수", null, true))));
        em.flush();

        assertThat(after.universities())
                .extracting(StudentProfileService.UniversityDetail::universityName)
                .containsExactly("고려대학교");
        assertThat(after.universities().get(0).passed()).isTrue();
    }

    @Test
    @DisplayName("★★ 합격증은 file_attachment id 만 참조한다 — 저장소를 따로 두지 않는다")
    void certificateReferencesExistingFile() {
        FileAttachment file = certificate();
        em.flush();

        var saved = profileService.save(branchAdmin, minji.getId(), basic(List.of(
                new UniversityCommand("고려대학교", "통계학과", "학업우수", file.getId(), true))));
        em.flush();

        var row = saved.universities().get(0);
        assertThat(row.certificateFileId()).isEqualTo(file.getId());
        assertThat(row.certificateFileName()).isEqualTo("합격증.pdf");
        // ★ 파일 쪽에도 주인이 적힌다 — 안 적으면 보관기간 정리에서 「어디에 붙은 파일인지」를
        //   알 수 없어 지울 수도 남길 수도 없다
        assertThat(file.getOwnerId()).isEqualTo(row.id());
    }

    @Test
    @DisplayName("★ 없는 파일 id 는 거절한다 — 통과시키면 합격증이 영영 안 열린다")
    void rejectsUnknownFile() {
        assertThatThrownBy(() -> profileService.save(branchAdmin, minji.getId(), basic(List.of(
                new UniversityCommand("고려대학교", null, null, 999_999L, null)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    @DisplayName("⚠️ 건강상태는 담임에게 가려지고 masked 가 실린다 — 민감정보 동의 문구 미확정")
    void healthIsMaskedForTeacher() {
        profileService.save(branchAdmin, minji.getId(), basic(List.of()));
        em.flush();
        var detail = profileService.find(branchAdmin, minji.getId()).orElseThrow();

        var forAdmin = StudentProfileResponse.of(detail, branchAdmin);
        var forTeacher = StudentProfileResponse.of(detail, teacher);

        assertThat(forAdmin.healthNote()).contains("에피펜");
        assertThat(forAdmin.masked()).isFalse();

        assertThat(forTeacher.healthNote()).isNull();
        assertThat(forTeacher.healthItems()).isNull();
        // ★ 플래그가 없으면 화면이 「저장이 안 됐다」로 읽어 담임이 다시 받으러 간다
        assertThat(forTeacher.masked()).isTrue();
        // 생활습관은 민감정보가 아니라 담임도 본다 — 상담에 쓰는 값이다
        assertThat(forTeacher.lifeNote()).isEqualTo("취침 01시 · 아침 결식");
    }

    @Test
    @DisplayName("★ 남의 지점 학생은 거절한다 — 등록 건 id 를 화면이 보낸다")
    void rejectsOtherBranch() {
        assertThatThrownBy(() -> profileService.save(branchAdmin, stranger.getId(),
                basic(List.of())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTHER_BRANCH_ACCESS_DENIED);

        assertThatThrownBy(() -> profileService.find(branchAdmin, stranger.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("★ 양식은 등록해도 꺼져 있다 — 미확정 양식(I-17)이 앱에 떠서는 안 된다")
    void formStartsInactive() {
        var form = profileService.createForm(branchAdmin, academy.getId(), year, null,
                "N수 신상기록부", Map.of("fields", List.of("sleep", "allergy")), null);
        em.flush();

        assertThat(form.isActive()).isFalse();
        assertThat(profileService.changeFormActive(branchAdmin, form.getId(), true).isActive())
                .isTrue();
    }

    @Test
    @DisplayName("★ 전 지점 공통 양식은 본사만 만든다 — 지점이 만들면 다른 지점 화면이 같이 바뀐다")
    void commonFormIsHeadquartersOnly() {
        assertThatThrownBy(() -> profileService.createForm(branchAdmin, null, year, null,
                "공통 양식", Map.of("fields", List.of("sleep")), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.STUDENT_PROFILE_FORM_SCOPE_FORBIDDEN);
    }

    @Test
    @DisplayName("★ 양식 없이도 작성된다 — I-17 확정을 기다리면 화면이 돌지 않는다")
    void worksWithoutForm() {
        var saved = profileService.save(branchAdmin, minji.getId(), basic(List.of()));
        em.flush();

        assertThat(saved.formId()).isNull();
        assertThat(saved.healthNote()).isNotBlank();
    }
}
