package com.dlab.api.admin.absence;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.entity.AbsenceReasonCategory;
import com.dlab.domain.attendance.repository.AbsenceReasonCategoryRepository;
import com.dlab.domain.user.repository.AcademyRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 사유 카테고리 마스터 (앱 시안 피드백 p3 · 3).
 *
 * <p>병결 · 가정사 · 학교행사처럼 <b>"왜 그랬는가"</b>를 고르는 값이다.
 * 결석·지각·조퇴·외출({@code AbsenceReasonType})과 <b>다른 축</b>이라 합치지 않는다 —
 * 같은 결석이라도 사유가 갈리고, 합치면 통계에서 "지각인데 병결"을 셀 수 없다.
 *
 * <p><b>값은 아직 안 정해졌다.</b> 클라이언트가 "기본 카테고리가 있거나"라고만 해서,
 * 정해지면 여기서 행만 넣으면 되게 마스터로 뒀다(상벌점 항목과 같은 방식).
 */
@Tag(name = "관리자 · 사유 카테고리")
@RestController
@RequestMapping("/api/v1/admin/absence-categories")
@RequiredArgsConstructor
public class AdminAbsenceCategoryController {

    private final AbsenceReasonCategoryRepository categoryRepository;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    /**
     * 목록. <b>전 지점 공통 + 그 지점 것</b>을 합쳐서 준다.
     *
     * @param activeOnly 기본은 전체다 — 관리 화면은 꺼둔 항목도 봐야 한다.
     *                   앱 드롭다운은 {@code true}로 부른다
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<CategoryResponse>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "false") boolean activeOnly) {

        return ApiResponse.success(categoryRepository
                .findUsable(me.requireAcademyScope(academyId), yearOf(year), activeOnly)
                .stream().map(CategoryResponse::from).toList());
    }

    /**
     * 등록.
     *
     * <p><b>전 지점 공통({@code academyId} 없음)은 본사만</b> 만든다 — 지점 관리자가 넣으면
     * 다른 지점 드롭다운에도 뜬다. 휴일 등록과 같은 규칙이다.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @Transactional
    public ApiResponse<CategoryResponse> create(@CurrentAccount AuthPrincipal me,
                                                @Valid @RequestBody CategoryRequest request) {
        if (request.academyId() == null && !me.allAcademy()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "전 지점 공통 카테고리는 본사만 등록할 수 있습니다.");
        }
        var academy = request.academyId() == null ? null
                : academyRepository.findById(me.requireAcademyScope(request.academyId()))
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        return ApiResponse.success(CategoryResponse.from(categoryRepository.save(
                new AbsenceReasonCategory(academy, yearOf(request.year()), request.name(),
                        request.sortOrder() == null ? 0 : request.sortOrder()))));
    }

    /** 수정. {@code null}은 "변경하지 않음"이다. */
    @PutMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @Transactional
    public ApiResponse<CategoryResponse> update(@CurrentAccount AuthPrincipal me,
                                                @PathVariable Long categoryId,
                                                @Valid @RequestBody CategoryUpdate request) {
        AbsenceReasonCategory category = require(me, categoryId);
        category.update(request.name(), request.sortOrder(), request.active());
        return ApiResponse.success(CategoryResponse.from(category));
    }

    /**
     * 삭제(soft).
     *
     * <p>물리 삭제하지 않는다 — 과거 사유가 이 행을 참조한다. 새 신청에서만 빼려면
     * {@code active=false}로 끄는 편이 낫다.
     */
    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @Transactional
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long categoryId) {
        require(me, categoryId).markDeleted();
        return ApiResponse.empty();
    }

    private AbsenceReasonCategory require(AuthPrincipal me, Long categoryId) {
        AbsenceReasonCategory category = categoryRepository.findActiveById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "사유 카테고리를 찾을 수 없습니다."));
        // 전 지점 공통은 본사만 손댄다 — 지점이 고치면 다른 지점 화면이 함께 바뀐다
        if (category.getAcademy() == null) {
            if (!me.allAcademy()) {
                throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
            }
        } else if (!me.canAccessAcademy(category.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return category;
    }

    private short yearOf(Integer year) {
        return year == null ? (short) LocalDate.now(clock).getYear() : year.shortValue();
    }

    /** @param academyId 비우면 <b>전 지점 공통</b>이다. 본사만 만들 수 있다 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AbsenceCategoryRequest")
    public record CategoryRequest(Long academyId, Integer year,
                                  @NotBlank(message = "이름은 필수입니다.")
                                  @Size(max = 50) String name,
                                  Short sortOrder) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "AbsenceCategoryUpdate")
    public record CategoryUpdate(@Size(max = 50) String name, Short sortOrder, Boolean active) {
    }

    /** @param nationwide 전 지점 공통인지. 지점 관리자는 이 항목을 못 고친다 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AbsenceCategoryResponse")
    public record CategoryResponse(Long id, Long academyId, boolean nationwide, short year,
                                   String name, short sortOrder, boolean active) {

        static CategoryResponse from(AbsenceReasonCategory c) {
            return new CategoryResponse(c.getId(),
                    c.getAcademy() == null ? null : c.getAcademy().getId(),
                    c.getAcademy() == null,
                    c.getYear(), c.getName(), c.getSortOrder(), c.isActive());
        }
    }
}
