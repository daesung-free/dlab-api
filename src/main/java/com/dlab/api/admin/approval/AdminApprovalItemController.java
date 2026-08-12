package com.dlab.api.admin.approval;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 승인 라우팅 정책 관리 (F-4.11-5).
 *
 * <p>지점 × 연도 × 신청유형마다 한 행이다. <b>행이 없으면 그 유형의 신청 자체가 거절된다</b> —
 * 사유신청·정기일정·방화벽이 전부 여기에 걸려 있다.
 *
 * <p><b>승인 주체는 아직 확정되지 않았다(I-12).</b> 방화벽(학부모 → 10분 후 담당선생님)만
 * 확정이고 나머지는 비워두면 된다 — 비어 있으면 그 신청이 막힌다는 것을 화면이 알려야 한다.
 */
@RestController
@RequestMapping("/api/v1/admin/approval-items")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminApprovalItemController {

    private final ApprovalItemService approvalItemService;

    /** 신청유형 3종을 전부 내린다. 안 정한 유형은 {@code configured=false}로 나온다. */
    @GetMapping
    public ApiResponse<List<ApprovalItemService.Row>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year) {
        return ApiResponse.success(approvalItemService.list(me, academyId, year));
    }

    /** 있으면 고치고 없으면 만든다. 유형당 1행이다. */
    @PutMapping("/{requestType}")
    public ApiResponse<Void> save(@CurrentAccount AuthPrincipal me,
                                  @PathVariable RequestType requestType,
                                  @Valid @RequestBody SaveRequest request) {
        approvalItemService.save(me, request.academyId(), request.year(), requestType,
                request.approverType(), request.timeoutMinutes(),
                request.escalationApproverType());
        return ApiResponse.empty();
    }

    /**
     * 정책 삭제.
     *
     * <p><b>삭제하면 그 유형의 신청이 전부 막힌다.</b> 화면에서 경고할 것.
     */
    @DeleteMapping("/{requestType}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable RequestType requestType,
                                    @RequestParam(required = false) Long academyId,
                                    @RequestParam short year) {
        approvalItemService.delete(me, academyId, year, requestType);
        return ApiResponse.empty();
    }

    /**
     * @param timeoutMinutes          에스컬레이션 대상과 <b>함께</b> 지정해야 한다.
     *                                한쪽만 채우면 저장은 되는데 에스컬레이션이 조용히 안 돈다
     * @param escalationApproverType  담당선생님({@code TEACHER})만 가능하다 —
     *                                학부모는 최대 1인이라 넘길 다른 학부모가 없다
     */
    public record SaveRequest(Long academyId,
                              @NotNull Short year,
                              @NotNull ApproverType approverType,
                              Short timeoutMinutes,
                              ApproverType escalationApproverType) {
    }
}
