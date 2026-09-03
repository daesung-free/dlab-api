package com.dlab.api.admin.penalty;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.search.PageSlicer;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.repository.PenaltyItemRepository;
import com.dlab.domain.penalty.service.PenaltyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 상벌점 관리 (F-4.1-2).
 *
 * <p>DSA에는 <b>수기 일괄 부여만</b> 있었고 자동 규칙이 없었다. 규칙엔진은
 * I-5(트리거→점수 매핑) 확정이 블로커라, 화면도 <i>"수기 부여 우선 + 자동규칙 UI는 자리만"</i>이다.
 */
@Tag(name = "관리자 · 상벌점 부여·조회")
@RestController
@RequestMapping("/api/v1/admin/penalties")
@RequiredArgsConstructor
public class AdminPenaltyController {

    private final PenaltyService penaltyService;
    private final PenaltyItemRepository penaltyItemRepository;

    /**
     * 목록 + 상단 합계. 합계는 <b>조회 조건 기준</b>이다(화면 명시).
     *
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @GetMapping
    public ApiResponse<PenaltyBoardResponse> board(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PenaltyCategory category,
            @RequestParam(required = false) PenaltySource source,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;

        var board = penaltyService.board(me, academyId, start, end, category, source, keyword, classId);
        boolean raw = PersonalDataPolicy.canViewRaw(me);

        List<PenaltyRowResponse> all = board.rows().stream()
                .map(p -> PenaltyRowResponse.of(p, raw)).toList();

        // summary 는 페이지 합계가 아니라 필터 전체 기준이다 — 상단 통계 타일이
        // 페이지를 넘길 때마다 값이 바뀌면 "이번 달 벌점 합계"라는 의미가 사라진다
        Map<String, Long> summary = Map.of(
                "plusTotal", (long) board.plusTotal(),
                "minusTotal", (long) board.minusTotal(),
                "autoCount", board.autoCount());

        if (size == null) {
            return ApiResponse.success(new PenaltyBoardResponse(all, summary, !raw));
        }
        var sliced = PageSlicer.of(all, page, size);
        return ApiResponse.success(
                new PenaltyBoardResponse(sliced.getContent(), summary, !raw),
                ApiResponse.PageMeta.of(sliced));
    }

    /**
     * 부여 가능한 항목. 화면 드롭다운이 쓴다.
     *
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @GetMapping("/items")
    public ApiResponse<List<PenaltyItemResponse>> items(@CurrentAccount AuthPrincipal me,
                                                 @RequestParam(required = false) Long academyId,
                                                 @RequestParam(required = false) Integer year) {
        Long scope = me.requireAcademyScope(academyId);
        short targetYear = year == null ? (short) LocalDate.now().getYear() : year.shortValue();

        return ApiResponse.success(penaltyItemRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByItemNameAsc(scope, targetYear)
                .stream().map(PenaltyItemResponse::from).toList());
    }

    /**
     * 선택 일괄 부여 (수기).
     *
     * <p>점수는 <b>항목 값 그대로</b>다 — 조정할 수 없다. 부여 시점 값을 복사해두므로
     * 나중에 항목 점수를 바꿔도 과거 이력이 소급해서 바뀌지 않는다.
     */
    @PostMapping
    public ApiResponse<Integer> grant(@CurrentAccount AuthPrincipal me,
                                      @Valid @RequestBody GrantRequest request) {
        PenaltyItem item = penaltyItemRepository.findById(request.itemId())
                .filter(i -> !i.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "상벌점 항목을 찾을 수 없습니다."));

        return ApiResponse.success(penaltyService
                .grantManually(me, request.enrollmentIds(), item, request.reason()).size());
    }

    /**
     * 부여 취소.
     *
     * <p>soft delete다 — 물리 삭제하면 "누가 왜 취소했나"를 추적할 수 없다.
     */
    @DeleteMapping("/{penaltyPointId}")
    public ApiResponse<Void> revoke(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long penaltyPointId) {
        penaltyService.revoke(me, penaltyPointId);
        return ApiResponse.success(null);
    }

    /** 학생별 내역. 상세 화면·앱 Daily Report가 함께 쓴다. */
    @GetMapping("/students/{enrollmentId}")
    public ApiResponse<StudentPenaltyResponse> byStudent(@CurrentAccount AuthPrincipal me,
                                                         @PathVariable Long enrollmentId) {
        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(new StudentPenaltyResponse(
                penaltyService.findByEnrollment(me, enrollmentId).stream()
                        .map(p -> PenaltyRowResponse.of(p, raw)).toList(),
                penaltyService.totalPoints(enrollmentId)));
    }

    public record GrantRequest(
            @NotEmpty List<Long> enrollmentIds,
            @NotNull Long itemId,
            @Size(max = 500) String reason
    ) {
    }

    public record PenaltyBoardResponse(List<PenaltyRowResponse> rows, Map<String, Long> summary, boolean masked) {
    }

    public record StudentPenaltyResponse(List<PenaltyRowResponse> rows, int totalPoints) {
    }

    /**
     * @param point 화면 표기용 부호. <b>벌점은 음수</b>로 내린다 —
     *              화면이 카테고리를 다시 보지 않고 그대로 찍는다
     */
    public record PenaltyRowResponse(
            Long id,
            Instant occurredAt,
            String studentNo,
            String name,
            PenaltyCategory category,
            String itemName,
            int point,
            String reason,
            PenaltySource source,
            Long grantedBy
    ) {
        static PenaltyRowResponse of(PenaltyPoint p, boolean raw) {
            boolean demerit = p.getPenaltyItem().getCategory() == PenaltyCategory.DEMERIT;
            int value = Math.abs(p.getPoints());

            return new PenaltyRowResponse(
                    p.getId(),
                    p.getOccurredAt(),
                    p.getEnrollment().getStudentNo(),
                    raw ? p.getEnrollment().getStudent().getName()
                            : Masking.name(p.getEnrollment().getStudent().getName()),
                    p.getPenaltyItem().getCategory(),
                    p.getPenaltyItem().getItemName(),
                    demerit ? -value : value,
                    p.getReason(),
                    p.getSource(),
                    p.getCreatedBy());
        }
    }

    public record PenaltyItemResponse(Long id, String itemName, PenaltyCategory category, int point) {
        static PenaltyItemResponse from(PenaltyItem i) {
            boolean demerit = i.getCategory() == PenaltyCategory.DEMERIT;
            int value = Math.abs(i.getPointValue());
            return new PenaltyItemResponse(i.getId(), i.getItemName(), i.getCategory(),
                    demerit ? -value : value);
        }
    }
}
