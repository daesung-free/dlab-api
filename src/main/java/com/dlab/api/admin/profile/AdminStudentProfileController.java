package com.dlab.api.admin.profile;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.profile.service.StudentProfileService;
import com.dlab.domain.profile.service.StudentProfileService.ProfileDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 신상기록부 (F-4.11-9).
 *
 * <p>프론트는 화면을 완성해 뒀는데 <b>저장할 곳이 없었다</b>.
 *
 * <h2>⚠️ 건강상태는 민감정보라 가려서 내린다</h2>
 * 수집 동의 문구가 <b>미확정</b>이라, 저장 구조는 두되 <b>원본 열람은 상위 관리자</b>
 * ({@code SUPER_ADMIN}·{@code BRANCH_ADMIN})로 제한한다({@code PersonalDataPolicy}).
 * 담임({@code TEACHER})에게는 건강 항목이 비어서 나가고 {@code masked = true} 가 실린다 —
 * <b>플래그가 없으면 화면이 「저장이 안 됐다」로 읽어</b> 담임이 다시 받으러 간다.
 *
 * <p>★ <b>담임도 쓴다.</b> 신상기록부는 담임이 상담 전에 채우는 서류라 지점 관리자로
 * 제한하면 쓸 수 없다. 민감 항목만 역할로 갈린다.
 */
@Tag(name = "관리자 · 신상기록부")
@RestController
@RequestMapping("/api/v1/admin/student-profiles")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
public class AdminStudentProfileController {

    private final StudentProfileService studentProfileService;

    /**
     * 한 학생의 신상기록부.
     *
     * <p>아직 안 썼으면 <b>404 다</b>. 빈 껍데기를 200 으로 내리면 화면이 「작성됨」으로
     * 읽어 상담 현황과 어긋난다.
     */
    @GetMapping("/{enrollmentId}")
    public ApiResponse<StudentProfileResponse> get(@CurrentAccount AuthPrincipal me,
                                                   @PathVariable Long enrollmentId) {
        var detail = studentProfileService.find(me, enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_PROFILE_NOT_FOUND));
        return ApiResponse.success(StudentProfileResponse.of(detail, me));
    }

    /**
     * 작성·수정.
     *
     * <p>★ <b>한 장을 통째로 받는다.</b> 보내지 않은 칸은 비워진다 — 부분 갱신으로 두면
     * 「지운 것」과 「안 보낸 것」을 구분할 수 없어 <b>잘못 적은 건강 메모를 지울 수 없다</b>.
     *
     * <p>목표대학도 <b>표 전체</b>를 보낸다. 뺀 줄은 soft delete 로 남는다.
     */
    @PutMapping("/{enrollmentId}")
    public ApiResponse<StudentProfileResponse> save(@CurrentAccount AuthPrincipal me,
                                                    @PathVariable Long enrollmentId,
                                                    @Valid @RequestBody SaveRequest request) {
        var detail = studentProfileService.save(me, enrollmentId, request.toCommand());
        return ApiResponse.success(StudentProfileResponse.of(detail, me));
    }

    /**
     * @param writtenAt  소급 입력용. 비우면 <b>최초 작성 시각을 건드리지 않는다</b> —
     *                   수정할 때마다 지금 시각으로 덮으면 「언제 받았나」가 사라진다
     * @param formId     쓴 양식. <b>비워도 된다</b> — I-17 미확정이라 양식 행 없이도 돌아야 한다
     * @param answers    양식이 정의한 나머지 항목의 답
     */
    // ★ 스키마 이름을 지정한다 — 다른 컨트롤러에도 `SaveRequest` 가 있어 겹치면
    //   생성되는 API 스펙에서 한쪽이 다른 쪽 모양으로 덮인다
    @Schema(name = "StudentProfileSaveRequest")
    public record SaveRequest(Long formId,
                              @Size(max = 2000) String healthNote,
                              Map<String, Object> healthItems,
                              @Size(max = 2000) String lifeNote,
                              Map<String, Object> lifeItems,
                              Map<String, Object> answers,
                              List<UniversityRequest> universities,
                              Instant writtenAt) {

        StudentProfileService.SaveCommand toCommand() {
            return new StudentProfileService.SaveCommand(formId, healthNote, healthItems,
                    lifeNote, lifeItems, answers,
                    universities == null ? List.of() : universities.stream()
                            .map(UniversityRequest::toCommand).toList(),
                    writtenAt);
        }
    }

    /**
     * @param certificateFileId 합격증. <b>{@code POST /admin/files/private} 로 먼저 올리고
     *                          그 id 를 보낸다</b> — 신상기록부는 파일을 보관하지 않는다
     * @param passed            직원의 합불 확인. <b>{@code null} 이 「아직 확인 안 함」</b>이고
     *                          {@code false} 는 불합격이다
     */
    @Schema(name = "StudentProfileUniversityRequest")
    public record UniversityRequest(@NotBlank String universityName,
                                    String departmentName,
                                    String screeningName,
                                    Long certificateFileId,
                                    Boolean passed) {

        StudentProfileService.UniversityCommand toCommand() {
            return new StudentProfileService.UniversityCommand(universityName, departmentName,
                    screeningName, certificateFileId, passed);
        }
    }

    /**
     * @param masked 민감 항목(건강상태)이 가려졌는지. ★ <b>화면이 「저장이 안 됐다」로
     *               오인하지 않게 서버가 알려준다</b> — 가려진 것과 비어 있는 것은 다르다
     */
    @Schema(name = "StudentProfileResponse")
    public record StudentProfileResponse(Long id, Long enrollmentId,
                                         String studentNo, String studentName,
                                         Long formId, String formName,
                                         String healthNote, Map<String, Object> healthItems,
                                         String lifeNote, Map<String, Object> lifeItems,
                                         Map<String, Object> answers,
                                         List<UniversityRow> universities,
                                         Instant writtenAt, Instant updatedAt,
                                         boolean masked) {

        public static StudentProfileResponse of(ProfileDetail d, AuthPrincipal me) {
            boolean raw = PersonalDataPolicy.canViewRaw(me);
            return new StudentProfileResponse(d.id(), d.enrollmentId(), d.studentNo(),
                    raw ? d.studentName() : Masking.name(d.studentName()),
                    d.formId(), d.formName(),
                    // ⚠️ 건강상태는 민감정보다. 동의 문구가 확정되기 전에는 상위 관리자만 본다
                    raw ? d.healthNote() : null,
                    raw ? d.healthItems() : null,
                    d.lifeNote(), d.lifeItems(), d.answers(),
                    d.universities().stream()
                            .map(u -> new UniversityRow(u.id(), u.sortOrder(),
                                    u.universityName(), u.departmentName(), u.screeningName(),
                                    u.certificateFileId(), u.certificateFileName(), u.passed()))
                            .toList(),
                    d.writtenAt(), d.updatedAt(), !raw);
        }
    }

    /**
     * @param certificateFileId 합격증 파일 id. <b>주소가 아니다</b> — 비공개 파일이라
     *                          볼 때마다 {@code GET /admin/files/{id}/url} 로 짧은 주소를 받는다
     */
    @Schema(name = "StudentProfileUniversityRow")
    public record UniversityRow(Long id, short sortOrder, String universityName,
                                String departmentName, String screeningName,
                                Long certificateFileId, String certificateFileName,
                                Boolean passed) {
    }
}
