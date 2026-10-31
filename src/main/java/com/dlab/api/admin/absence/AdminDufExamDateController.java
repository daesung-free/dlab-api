package com.dlab.api.admin.absence;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.entity.DufExamDate;
import com.dlab.domain.attendance.repository.DufExamDateRepository;
import com.dlab.domain.user.repository.AcademyRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ★★ 더프리미엄 <b>응시일</b> 관리 (디랩 1029 4-4).
 *
 * <p>★ 쓰임은 둘이다 — ① 더프 조퇴 신청을 <b>그 날에만</b> 받는다
 * ② <b>전일(-1)·당일(-2)</b> 벌점을 가른다.
 *
 * <p>★ <b>더프는 월례고사</b>라 매월 들어온다 — ⚠️ 등록하지 않으면
 * <b>그 달 더프 조퇴를 아무도 신청할 수 없다.</b>
 */
@Tag(name = "관리자 - 더프 응시일")
@RestController
@RequestMapping("/api/v1/admin/duf-exam-dates")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminDufExamDateController {

    private final DufExamDateRepository repository;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    /**
     * 목록 — ★ <b>그 지점 것 + 전 지점 공통</b>을 함께 내린다.
     *
     * <p>⚠️ 공통을 빼면 <b>「왜 이 날은 신청이 되지」</b>에 답할 수 없다.
     */
    @GetMapping
    public ApiResponse<List<DufExamDateView>> list(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Long academyId,
                                                   @RequestParam(required = false) Short year) {
        Long scope = me.requireAcademyScope(academyId);
        short targetYear = year == null ? (short) LocalDate.now(clock).getYear() : year;
        return ApiResponse.success(repository.findForAcademy(scope, targetYear).stream()
                .map(DufExamDateView::from).toList());
    }

    /**
     * 등록.
     *
     * <p>★ {@code academyId} 를 <b>비우면 전 지점 공통</b>이다 — ⚠️ 그건
     * <b>최고관리자만</b> 넣을 수 있다. 지점 관리자가 공통을 넣으면
     * <b>다른 지점 신청까지 열린다.</b>
     */
    @PostMapping
    public ApiResponse<Long> create(@CurrentAccount AuthPrincipal me,
                                    @Valid @RequestBody CreateDufExamDate request) {
        if (request.academyId() == null) {
            if (!me.allAcademy()) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "전 지점 공통 응시일은 본사만 등록할 수 있습니다.");
            }
            return ApiResponse.success(repository.save(new DufExamDate(
                    null, yearOf(request.examDate()), request.examDate(),
                    request.note())).getId());
        }
        Long scope = me.requireAcademyScope(request.academyId());
        var academy = academyRepository.findById(scope)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        return ApiResponse.success(repository.save(new DufExamDate(
                academy, yearOf(request.examDate()), request.examDate(),
                request.note())).getId());
    }

    /**
     * 치운다 — ★ <b>soft delete</b> 다.
     *
     * <p>⚠️ 물리 삭제하면 <b>그 날 신청된 더프 조퇴가 어느 응시일 기준이었는지</b>
     * 추적이 끊긴다.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        DufExamDate found = repository.findById(id)
                .filter(d -> !d.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "응시일을 찾을 수 없습니다."));
        if (found.getAcademy() == null) {
            if (!me.allAcademy()) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "전 지점 공통 응시일은 본사만 치울 수 있습니다.");
            }
        } else {
            me.requireAcademyScope(found.getAcademy().getId());
        }
        found.markDeleted();
        return ApiResponse.success(null);
    }

    private short yearOf(LocalDate date) {
        return (short) date.getYear();
    }

    public record CreateDufExamDate(
            /** ★ 비우면 전 지점 공통 — ⚠️ 본사만 넣을 수 있다. */
            Long academyId,
            @NotNull(message = "응시일은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate examDate,
            /** 회차 이름. ★ 「7월 더프」처럼 적으면 목록에서 고르기 쉽다. */
            String note) {
    }

    /** @param common ★ {@code true} 면 전 지점 공통이다 */
    public record DufExamDateView(Long id, Long academyId, boolean common,
                                  LocalDate examDate, String note) {

        static DufExamDateView from(DufExamDate d) {
            return new DufExamDateView(d.getId(),
                    d.getAcademy() == null ? null : d.getAcademy().getId(),
                    d.getAcademy() == null,
                    d.getExamDate(), d.getNote());
        }
    }
}
