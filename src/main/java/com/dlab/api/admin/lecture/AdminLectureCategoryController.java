package com.dlab.api.admin.lecture;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.lecture.entity.LectureCategory;
import com.dlab.domain.lecture.repository.LectureCategoryRepository;
import com.dlab.domain.user.repository.AcademyRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 특강 유형 마스터 — 단과 · 실전 · 해설 …
 *
 * <p><b>{@code LectureType}(특강/설명회)과 다른 축이다.</b> 그쪽은 흐름이 같아 한 테이블에
 * 두고 구분만 하는 값이라 늘어나지 않는다. 여기는 특강 안에서의 성격이고 학원이 늘린다.
 *
 * <p><b>세 가지로 고정하지 않는다</b> — 클라이언트 요청대로 관리자가 추가한다.
 * enum 이면 유형 하나 늘릴 때마다 마이그레이션을 새로 쓰고 배포해야 한다.
 */
@Tag(name = "관리자 · 특강 유형")
@RestController
@RequestMapping("/api/v1/admin/lecture-categories")
@RequiredArgsConstructor
public class AdminLectureCategoryController {

    private final LectureCategoryRepository categoryRepository;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    /**
     * 목록. <b>전 지점 공통 + 그 지점 것</b>을 합쳐서 준다.
     *
     * @param activeOnly 기본은 전체다 — 관리 화면은 꺼둔 것도 봐야 한다.
     *                   개설 화면 드롭다운은 {@code true}로 부른다
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

    /** 등록. <b>전 지점 공통은 본사만</b> — 지점이 넣으면 다른 지점 드롭다운에도 뜬다. */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @Transactional
    public ApiResponse<CategoryResponse> create(@CurrentAccount AuthPrincipal me,
                                                @Valid @RequestBody CategoryRequest request) {
        if (request.academyId() == null && !me.allAcademy()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "전 지점 공통 유형은 본사만 등록할 수 있습니다.");
        }
        var academy = request.academyId() == null ? null
                : academyRepository.findById(me.requireAcademyScope(request.academyId()))
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        return ApiResponse.success(CategoryResponse.from(categoryRepository.save(
                new LectureCategory(academy, yearOf(request.year()), request.name(),
                        request.sortOrder() == null ? 0 : request.sortOrder()))));
    }

    /** 수정. {@code null}은 "변경하지 않음"이다. */
    @PutMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @Transactional
    public ApiResponse<CategoryResponse> update(@CurrentAccount AuthPrincipal me,
                                                @PathVariable Long categoryId,
                                                @Valid @RequestBody CategoryUpdate request) {
        LectureCategory category = require(me, categoryId);
        category.update(request.name(), request.sortOrder(), request.active());
        return ApiResponse.success(CategoryResponse.from(category));
    }

    /**
     * 삭제(soft).
     *
     * <p>물리 삭제하지 않는다 — 과거 특강이 이 행을 참조한다.
     * 새 특강에서만 빼려면 {@code active=false}로 끄는 편이 낫다.
     */
    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    @Transactional
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long categoryId) {
        require(me, categoryId).markDeleted();
        return ApiResponse.empty();
    }

    private LectureCategory require(AuthPrincipal me, Long categoryId) {
        LectureCategory category = categoryRepository.findActiveById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "특강 유형을 찾을 수 없습니다."));
        // 전 지점 공통은 본사만 손댄다 — 지점이 고치면 다른 지점 화면도 함께 바뀐다
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
    public record CategoryRequest(Long academyId, Integer year,
                                  @NotBlank(message = "이름은 필수입니다.")
                                  @Size(max = 50) String name,
                                  Short sortOrder) {
    }

    public record CategoryUpdate(@Size(max = 50) String name, Short sortOrder, Boolean active) {
    }

    public record CategoryResponse(Long id, Long academyId, boolean nationwide, short year,
                                   String name, short sortOrder, boolean active) {

        static CategoryResponse from(LectureCategory c) {
            return new CategoryResponse(c.getId(),
                    c.getAcademy() == null ? null : c.getAcademy().getId(),
                    c.getAcademy() == null,
                    c.getYear(), c.getName(), c.getSortOrder(), c.isActive());
        }
    }
}
