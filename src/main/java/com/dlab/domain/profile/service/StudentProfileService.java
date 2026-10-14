package com.dlab.domain.profile.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.file.repository.FileAttachmentRepository;
import com.dlab.domain.file.service.FileAttachmentService;
import com.dlab.domain.profile.entity.StudentProfile;
import com.dlab.domain.profile.entity.StudentProfileForm;
import com.dlab.domain.profile.entity.TargetUniversity;
import com.dlab.domain.profile.repository.StudentProfileFormRepository;
import com.dlab.domain.profile.repository.StudentProfileRepository;
import com.dlab.domain.profile.repository.TargetUniversityRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 신상기록부 (F-4.11-9).
 *
 * <p>프론트는 화면을 완성해 뒀는데 <b>저장할 곳이 없었다</b> — 서버에 있던 것은 상담 현황의
 * 작성 여부 {@code boolean} 하나였고 그것조차 늘 {@code null} 이었다.
 *
 * <h2>★ 담아 두는 것과 담지 않는 것</h2>
 * <ul>
 *   <li><b>담는다</b> — 건강상태 · 생활습관 · 목표대학(+합격증)</li>
 *   <li><b>담지 않는다</b> — <b>내신</b>. {@code student_grade_submission.main_subject_average}
 *       한 칸이 정본이다. 여기 또 두면 장학 취소 판정이 어느 쪽을 보는지 정해지지 않는다</li>
 *   <li><b>파일</b> — {@code domain/file} 을 그대로 쓴다. 참조만 한다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentProfileService {

    /**
     * 합격증 파일의 소유 종류.
     *
     * <p>{@code file_attachment.owner_type} 이 enum 이 아니라 문자열인 이유가 이것이다 —
     * 도메인이 자기 이름을 넣으므로 파일 도메인이 신상기록부를 알 필요가 없다.
     */
    public static final String CERTIFICATE_OWNER_TYPE = "STUDENT_PROFILE_CERTIFICATE";

    private final StudentProfileRepository profileRepository;
    private final StudentProfileFormRepository formRepository;
    private final TargetUniversityRepository universityRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final FileAttachmentRepository fileRepository;
    private final FileAttachmentService fileAttachmentService;
    private final com.dlab.domain.user.repository.AcademyRepository academyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ── 작성본 ────────────────────────────────────────────────

    /** 그 학생의 신상기록부. 아직 안 썼으면 빈 값이다. */
    @Transactional(readOnly = true)
    public Optional<ProfileDetail> find(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return profileRepository.findByEnrollment(enrollment.getId()).map(this::detailOf);
    }

    /**
     * 작성·수정.
     *
     * <h2>★ 한 장을 통째로 받는다 (부분 갱신이 아니다)</h2>
     * 화면이 신상기록부 전체를 보내는 모양이다. 부분 갱신으로 두면 <b>「지운 것」과
     * 「안 보낸 것」을 구분할 방법이 없어</b> 잘못 적은 건강 메모를 지울 수 없게 된다.
     *
     * <h2>★ 등록 건마다 한 장이다</h2>
     * 두 장이면 상담 현황의 작성 여부가 어느 쪽을 보는지 정해지지 않고, 담임이 고친 내용이
     * 다른 행에 남아 화면에서 사라진다. 그래서 있으면 갱신한다.
     */
    @Transactional
    public ProfileDetail save(AuthPrincipal me, Long enrollmentId, SaveCommand command) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        StudentProfileForm form = resolveForm(command.formId());

        StudentProfile profile = profileRepository.findByEnrollment(enrollment.getId())
                .orElseGet(() -> profileRepository.save(
                        new StudentProfile(enrollment, form, Instant.now(clock))));

        profile.overwrite(form,
                command.healthNote(), json(command.healthItems()),
                command.lifeNote(), json(command.lifeItems()),
                json(command.answers()));
        if (command.writtenAt() != null) {
            // 소급 입력 — 종이로 받아둔 날짜를 데스크가 넣는다
            profile.changeWrittenAt(command.writtenAt());
        }

        replaceUniversities(profile, command.universities());

        log.info("신상기록부 저장: profile={}, enrollment={}, 목표대학={}건, 처리자={}",
                profile.getId(), enrollment.getId(),
                command.universities() == null ? 0 : command.universities().size(),
                me.accountId());
        return detailOf(profile);
    }

    /**
     * 목표대학 줄 교체.
     *
     * <h2>★ 기존 줄은 soft delete 한다 — 물리 삭제가 아니다</h2>
     * 「그때 무엇을 목표로 썼나」가 상담 근거이고, <b>합격증 파일이 그 줄에 매달려 있어</b>
     * 행이 사라지면 파일이 고아가 된다.
     *
     * <h2>★ id 로 짝을 맞춰 갱신하지 않는다</h2>
     * 화면이 표 전체를 보내므로 지워진 줄·순서 변경·새 줄이 한 번에 들어온다. 짝을 맞추려
     * 하면 <b>「id 가 없는 줄은 새 줄인가 지워진 줄인가」가 애매해진다</b> — 통째로 다시 쓴다.
     */
    private void replaceUniversities(StudentProfile profile, List<UniversityCommand> commands) {
        universityRepository.findByProfile(profile.getId()).forEach(TargetUniversity::markDeleted);
        if (commands == null || commands.isEmpty()) {
            return;
        }

        short order = 0;
        for (UniversityCommand command : commands) {
            FileAttachment certificate = requireFile(command.certificateFileId());
            TargetUniversity saved = universityRepository.save(new TargetUniversity(
                    profile, order++, command.universityName(), command.departmentName(),
                    command.screeningName(), certificate, command.passed()));

            if (certificate != null) {
                // ★ 파일 쪽에도 주인을 적어 둔다. 안 적으면 파일 목록·보관기간 정리에서
                //   「어디에 붙은 파일인지」를 알 수 없어 지울 수도 남길 수도 없게 된다
                fileAttachmentService.attach(certificate.getId(), saved.getId());
            }
        }
    }

    /**
     * 작성된 등록 건 id — 상담 현황의 「신상기록부 작성 여부」.
     *
     * <p>★ <b>학생 한 명씩 묻지 않는다.</b> 상담 현황은 재원생 전원이 대상이라 줄마다
     * 조회하면 수백 번 쿼리가 나간다.
     */
    @Transactional(readOnly = true)
    public java.util.Set<Long> writtenEnrollmentIds(Long academyId, short year) {
        return new java.util.HashSet<>(
                profileRepository.findWrittenEnrollmentIds(academyId, year));
    }

    // ── 양식 (I-17 미확정) ────────────────────────────────────

    /**
     * 양식 목록. 지점 양식 + 전 지점 공통을 합쳐서 본다.
     *
     * <p>I-17(4종 양식-학년 매핑)이 확정되기 전에는 <b>공통 한 행</b>만 있는 것이 정상이다.
     */
    @Transactional(readOnly = true)
    public List<StudentProfileForm> forms(AuthPrincipal me, Long academyId, short year) {
        return formRepository.findByScope(me.requireAcademyScope(academyId), year);
    }

    /**
     * 양식 등록.
     *
     * <p>★ <b>수정 API 를 두지 않았다.</b> 항목을 고치면 <b>이미 작성한 학생들이 무엇에
     * 답했는지가 바뀐다</b> — 약관과 같은 판단이라 버전을 올려 새 행을 넣는다.
     *
     * <p>등록만 하면 쓰이지 않는다({@code active} 기본값이 {@code false}) — 켜는 것은
     * 별 호출이다.
     */
    @Transactional
    public StudentProfileForm createForm(AuthPrincipal me, Long academyId, short year,
                                         com.dlab.domain.user.entity.GradeType gradeType,
                                         String name, Map<String, Object> schema,
                                         Short version) {
        // ★ 전 지점 공통(academyId 없음)은 본사만. 지점 관리자가 공통 행을 만들면
        //   나머지 지점 화면이 같이 바뀐다(성적 양식과 같은 판단)
        if (academyId == null && !me.hasRole(com.dlab.common.security.Role.SUPER_ADMIN)) {
            throw new BusinessException(ErrorCode.STUDENT_PROFILE_FORM_SCOPE_FORBIDDEN);
        }
        if (academyId != null && !me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (schema == null || schema.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "양식 항목을 넣어 주세요.");
        }

        var academy = academyId == null ? null
                : academyRepository.findById(academyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        StudentProfileForm saved = formRepository.save(new StudentProfileForm(
                academy, year, gradeType, name, json(schema),
                version == null ? (short) 1 : version));
        log.info("신상기록부 양식 등록: id={}, academy={}, year={}, grade={}, v={}, 처리자={}",
                saved.getId(), academyId, year, gradeType, saved.getVersion(), me.accountId());
        return saved;
    }

    /** 켜기·끄기. 켜지 않은 양식은 쓰이지 않는다. */
    @Transactional
    public StudentProfileForm changeFormActive(AuthPrincipal me, Long formId, boolean active) {
        StudentProfileForm form = formRepository.findActiveById(formId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_PROFILE_FORM_NOT_FOUND));
        if (form.getAcademy() == null) {
            if (!me.hasRole(com.dlab.common.security.Role.SUPER_ADMIN)) {
                throw new BusinessException(ErrorCode.STUDENT_PROFILE_FORM_SCOPE_FORBIDDEN);
            }
        } else if (!me.canAccessAcademy(form.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        form.changeActive(active);
        log.info("신상기록부 양식 {}: id={}, 처리자={}", active ? "사용" : "중지", formId,
                me.accountId());
        return form;
    }

    // ── 내부 ──────────────────────────────────────────────────

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        // ★ 학생 id 를 화면이 보낸다 — 값만 바꿔 보내면 남의 지점 기록을 읽거나 만들 수 있다
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    private StudentProfileForm resolveForm(Long formId) {
        if (formId == null) {
            // ★ 양식 없이도 작성이 돌아야 한다 — I-17 미확정이라 지금 화면은 고정 항목이다
            return null;
        }
        return formRepository.findActiveById(formId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_PROFILE_FORM_NOT_FOUND));
    }

    private FileAttachment requireFile(Long fileId) {
        if (fileId == null) {
            return null;
        }
        return fileRepository.findActiveById(fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
    }

    private String json(Map<String, Object> value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String raw) {
        return raw == null ? null : objectMapper.readValue(raw, Map.class);
    }

    private ProfileDetail detailOf(StudentProfile profile) {
        StudentEnrollment enrollment = profile.getEnrollment();
        return new ProfileDetail(profile.getId(), enrollment.getId(),
                enrollment.getStudentNo(), enrollment.getStudentName(),
                profile.getForm() == null ? null : profile.getForm().getId(),
                profile.getForm() == null ? null : profile.getForm().getName(),
                profile.getHealthNote(), map(profile.getHealthJson()),
                profile.getLifeNote(), map(profile.getLifeJson()),
                map(profile.getAnswersJson()),
                universityRepository.findByProfile(profile.getId()).stream()
                        .map(u -> new UniversityDetail(u.getId(), u.getSortOrder(),
                                u.getUniversityName(), u.getDepartmentName(),
                                u.getScreeningName(),
                                u.getCertificateFile() == null ? null
                                        : u.getCertificateFile().getId(),
                                u.getCertificateFile() == null ? null
                                        : u.getCertificateFile().getOriginalName(),
                                u.getPassed()))
                        .toList(),
                profile.getWrittenAt(), profile.getUpdatedAt());
    }

    /**
     * @param writtenAt 소급 입력용. 비우면 <b>최초 작성 시각을 건드리지 않는다</b> —
     *                  수정할 때마다 지금 시각으로 덮으면 「언제 받았나」가 사라진다
     * @param answers   양식({@code formId})이 정의한 나머지 항목의 답
     */
    public record SaveCommand(Long formId,
                              String healthNote, Map<String, Object> healthItems,
                              String lifeNote, Map<String, Object> lifeItems,
                              Map<String, Object> answers,
                              List<UniversityCommand> universities,
                              Instant writtenAt) {
    }

    /** @param passed {@code null} 이 「아직 확인 안 함」이다 — {@code false} 는 불합격이다 */
    public record UniversityCommand(String universityName, String departmentName,
                                    String screeningName, Long certificateFileId,
                                    Boolean passed) {
    }

    /**
     * 조회 결과.
     *
     * <p>JSON 칸을 {@code Map} 으로 돌려준다 — 문자열로 내리면 화면이 한 번 더 파싱해야 하고,
     * 그 파싱 실패가 화면마다 다르게 처리된다.
     */
    public record ProfileDetail(Long id, Long enrollmentId, String studentNo, String studentName,
                                Long formId, String formName,
                                String healthNote, Map<String, Object> healthItems,
                                String lifeNote, Map<String, Object> lifeItems,
                                Map<String, Object> answers,
                                List<UniversityDetail> universities,
                                Instant writtenAt, Instant updatedAt) {
    }

    public record UniversityDetail(Long id, short sortOrder, String universityName,
                                   String departmentName, String screeningName,
                                   Long certificateFileId, String certificateFileName,
                                   Boolean passed) {
    }
}
