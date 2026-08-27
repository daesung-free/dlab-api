package com.dlab.api.admin.penalty;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyMasterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 상벌점 항목·규칙 관리 (F-4.1-3, I-5).
 *
 * <p>클라이언트 답변대로 <b>점수를 화면에서 직접 입력</b>한다.
 *
 * <p><b>항목만 만들면 자동부여는 안 돈다</b> — 규칙(어떤 상황에 그 항목을 준다)까지
 * 만들고 켜야 한다. 화면에서 이 순서를 안내할 것.
 */
@Tag(name = "관리자 · 상벌점 항목·규칙 (F-4.1-3)")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminPenaltyMasterController {

    private final PenaltyMasterService masterService;

    // ── 항목 ──────────────────────────────────────────────────

    /** 상벌점 항목 목록. */
    @GetMapping("/penalty-items")
    public ApiResponse<List<ItemRow>> items(@CurrentAccount AuthPrincipal me,
                                            @RequestParam(required = false) Long academyId,
                                            @RequestParam short year) {
        return ApiResponse.success(masterService.items(me, academyId, year)
                .stream().map(ItemRow::from).toList());
    }

    /**
     * 항목 생성.
     *
     * <p>벌점을 양수로 보내도 음수로 저장된다 — 통계가 부호로 상점·벌점을 가른다.
     */
    @PostMapping("/penalty-items")
    public ApiResponse<ItemRow> createItem(@CurrentAccount AuthPrincipal me,
                                           @Valid @RequestBody ItemRequest request) {
        return ApiResponse.success(ItemRow.from(masterService.createItem(
                me, request.academyId(), request.year(), request.itemName(),
                request.point(), request.category())));
    }

    /** <b>이미 부여된 상벌점은 안 바뀐다</b> — 부여 시점 점수를 복사해두기 때문이다. */
    @PutMapping("/penalty-items/{itemId}")
    public ApiResponse<ItemRow> updateItem(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long itemId,
                                           @Valid @RequestBody ItemRequest request) {
        return ApiResponse.success(ItemRow.from(masterService.updateItem(
                me, itemId, request.itemName(), request.point(), request.category())));
    }

    /** 상벌점 항목 삭제(soft). 과거 부여 이력이 참조한다. */
    @DeleteMapping("/penalty-items/{itemId}")
    public ApiResponse<Void> deleteItem(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long itemId) {
        masterService.deleteItem(me, itemId);
        return ApiResponse.empty();
    }

    // ── 규칙 ──────────────────────────────────────────────────

    /** 꺼진 규칙까지 전부. 화면이 on/off 토글을 그린다. */
    @GetMapping("/penalty-rules")
    public ApiResponse<List<RuleRow>> rules(@CurrentAccount AuthPrincipal me,
                                            @RequestParam(required = false) Long academyId,
                                            @RequestParam short year) {
        return ApiResponse.success(masterService.rules(me, academyId, year)
                .stream().map(RuleRow::from).toList());
    }

    /** <b>기본은 꺼짐이다</b> — 검증 전 규칙이 전교생에게 벌점을 뿌리지 않게. */
    @PostMapping("/penalty-rules")
    public ApiResponse<RuleRow> createRule(@CurrentAccount AuthPrincipal me,
                                           @Valid @RequestBody RuleRequest request) {
        return ApiResponse.success(RuleRow.from(masterService.createRule(
                me, request.academyId(), request.year(), request.triggerType(),
                request.triggerCondition(), request.penaltyItemId())));
    }

    /** 규칙 수정. */
    @PutMapping("/penalty-rules/{ruleId}")
    public ApiResponse<RuleRow> updateRule(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long ruleId,
                                           @Valid @RequestBody RuleRequest request) {
        return ApiResponse.success(RuleRow.from(masterService.updateRule(
                me, ruleId, request.triggerType(), request.triggerCondition(),
                request.penaltyItemId())));
    }

    /** 켜고 끄기. 지우지 않고 끄면 과거 부여 근거가 남는다. */
    @PatchMapping("/penalty-rules/{ruleId}/active")
    public ApiResponse<RuleRow> toggleRule(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long ruleId,
                                           @RequestParam boolean active) {
        return ApiResponse.success(RuleRow.from(masterService.toggleRule(me, ruleId, active)));
    }

    /** 규칙 삭제(soft). */
    @DeleteMapping("/penalty-rules/{ruleId}")
    public ApiResponse<Void> deleteRule(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long ruleId) {
        masterService.deleteRule(me, ruleId);
        return ApiResponse.empty();
    }

    /**
     * @param point 화면은 절댓값으로 보내도 되고 부호를 실어도 된다. 저장은 구분을 따른다
     */
    public record ItemRequest(Long academyId,
                              @NotNull Short year,
                              @NotBlank @Size(max = 100) String itemName,
                              @NotNull Integer point,
                              @NotNull PenaltyCategory category) {
    }

    /**
     * @param triggerCondition 출결이면 {@code att_gn}(A=지각 등), 루틴이면 결과 상태,
     *                         정기일정이면 {@code NOT_RECOGNIZED}
     */
    public record RuleRequest(Long academyId,
                              @NotNull Short year,
                              @NotNull PenaltyTriggerType triggerType,
                              @NotBlank @Size(max = 100) String triggerCondition,
                              @NotNull Long penaltyItemId) {
    }

    /** @param point 저장된 부호 그대로다 — 벌점은 음수 */
    public record ItemRow(Long id, String itemName, PenaltyCategory category, int point) {

        static ItemRow from(PenaltyItem i) {
            return new ItemRow(i.getId(), i.getItemName(), i.getCategory(), i.getPointValue());
        }
    }

    /** @param active {@code false}면 만들어만 두고 안 도는 규칙이다 */
    public record RuleRow(Long id, PenaltyTriggerType triggerType, String triggerCondition,
                          Long penaltyItemId, String itemName, int point, boolean active) {

        static RuleRow from(PenaltyRule r) {
            return new RuleRow(r.getId(), r.getTriggerType(), r.getTriggerCondition(),
                    r.getPenaltyItem().getId(), r.getPenaltyItem().getItemName(),
                    r.getPenaltyItem().getPointValue(), r.isActive());
        }
    }
}
