package com.dlab.api.admin.profile;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.profile.entity.StudentProfileForm;
import com.dlab.domain.profile.service.StudentProfileService;
import com.dlab.domain.user.entity.GradeType;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 신상기록부 양식 (F-4.11-9 · I-17 미확정).
 *
 * <h2>★ 왜 양식을 데이터로 두나</h2>
 * 4종 양식과 학년 매핑(I-17)이 <b>아직 미확정</b>이다. 항목을 컬럼으로 박으면 확정될 때마다
 * 마이그레이션을 새로 쓰게 되고, 그때 <b>과거 학생이 무엇에 답했는지가 흐려진다</b>.
 * 확정되면 여기서 <b>행만 넣는다</b>.
 *
 * <h2>★ 수정 API 가 없다</h2>
 * 항목을 고치면 <b>이미 작성한 학생들이 무엇에 답했는지가 바뀐다</b> — 약관과 같은 판단이라
 * 버전을 올려 새 행을 넣는다. 오타 수정도 버전을 올린다.
 */
@Tag(name = "관리자 · 신상기록부 양식")
@RestController
@RequestMapping("/api/v1/admin/student-profile-forms")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminStudentProfileFormController {

    private final StudentProfileService studentProfileService;

    /**
     * 양식 목록 — 지점 양식 + 전 지점 공통.
     *
     * <p>I-17 확정 전에는 <b>공통 한 행만 있는 것이 정상</b>이다.
     *
     * <p>★ <b>담임도 읽는다.</b> 신상기록부 <b>작성은 담임이 하는데</b> 양식을 못 읽으면
     * 입력 칸이 뜨지 않는다 — 쓰기만 열고 읽기를 막아 둔 셈이었다.
     *
     * <p>⚠️ 양식 <b>등록·수정</b>은 그대로 본사·지점관리자다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
    @GetMapping
    public ApiResponse<List<FormRow>> list(@CurrentAccount AuthPrincipal me,
                                           @RequestParam(required = false) Long academyId,
                                           @RequestParam short year) {
        return ApiResponse.success(studentProfileService.forms(me, academyId, year).stream()
                .map(FormRow::of).toList());
    }

    /**
     * 양식 등록.
     *
     * <p>★ <b>등록만 하면 쓰이지 않는다</b>({@code active} 기본값 {@code false}).
     * 미확정 양식이 앱에 떠서 학생이 쓰기 시작하면 확정본과 어긋난 답이 쌓인다 —
     * 켜는 것은 별 호출이다.
     */
    @PostMapping
    public ApiResponse<FormRow> create(@CurrentAccount AuthPrincipal me,
                                       @Valid @RequestBody FormRequest request) {
        return ApiResponse.success(FormRow.of(studentProfileService.createForm(
                me, request.academyId(), request.year(), request.gradeType(),
                request.name(), request.schema(), request.version())));
    }

    /** 켜기·끄기. */
    @PatchMapping("/{formId}/active")
    public ApiResponse<FormRow> changeActive(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long formId,
                                             @RequestParam boolean active) {
        return ApiResponse.success(
                FormRow.of(studentProfileService.changeFormActive(me, formId, active)));
    }

    /**
     * @param academyId 비우면 <b>전 지점 공통</b> — 본사만 만들 수 있다
     * @param gradeType 비우면 <b>전 학년 공통</b>. I-17 확정 전에는 비워 둔다
     * @param schema    항목 정의. 화면이 이걸 읽어 입력칸을 그린다
     * @param version   비우면 1. <b>고칠 때는 버전을 올려 새로 등록</b>한다
     */
    @Schema(name = "StudentProfileFormRequest")
    public record FormRequest(Long academyId,
                              short year,
                              GradeType gradeType,
                              @NotBlank String name,
                              Map<String, Object> schema,
                              Short version) {
    }

    /** @param academyId {@code null} 이면 전 지점 공통, {@code gradeType} 이 null 이면 전 학년 */
    @Schema(name = "StudentProfileFormRow")
    public record FormRow(Long id, Long academyId, short year, GradeType gradeType,
                          String name, String schemaJson, short version, boolean active) {

        static FormRow of(StudentProfileForm f) {
            return new FormRow(f.getId(),
                    f.getAcademy() == null ? null : f.getAcademy().getId(),
                    f.getYear(), f.getGradeType(), f.getName(), f.getSchemaJson(),
                    f.getVersion(), f.isActive());
        }
    }
}
