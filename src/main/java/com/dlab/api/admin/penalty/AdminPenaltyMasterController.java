package com.dlab.api.admin.penalty;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.entity.TriggerCondition;
import com.dlab.domain.penalty.service.PenaltyMasterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
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
        List<com.dlab.domain.penalty.entity.PenaltyItem> items =
                masterService.items(me, academyId, year);
        // ★ 자동 부여 여부를 한 번에 받는다 — 항목마다 조회하면 56개면 57 쿼리다
        java.util.Set<Long> automatic = masterService.automaticItemIds(items);
        return ApiResponse.success(items.stream()
                .map(item -> ItemRow.from(item, automatic.contains(item.getId()))).toList());
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
                request.point(), request.category(),
                request.groupName(), request.description())));
    }

    /** <b>이미 부여된 상벌점은 안 바뀐다</b> — 부여 시점 점수를 복사해두기 때문이다. */
    @PutMapping("/penalty-items/{itemId}")
    public ApiResponse<ItemRow> updateItem(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long itemId,
                                           @Valid @RequestBody ItemRequest request) {
        return ApiResponse.success(ItemRow.from(masterService.updateItem(
                me, itemId, request.itemName(), request.point(), request.category(),
                request.groupName(), request.description())));
    }

    /** 공통 항목 목록을 다른 지점에 복사한다. 이미 같은 이름인 항목은 건너뛴다. */
    @PostMapping("/penalty-items/copy")
    public ApiResponse<List<ItemRow>> copyItems(@CurrentAccount AuthPrincipal me,
                                                @Valid @RequestBody CopyItemsRequest request) {
        List<com.dlab.domain.penalty.entity.PenaltyItem> copies = masterService.copyItems(
                me, request.sourceAcademyId(), request.targetAcademyId(), request.year());
        java.util.Set<Long> automatic = masterService.automaticItemIds(copies);
        return ApiResponse.success(copies.stream()
                .map(item -> ItemRow.from(item, automatic.contains(item.getId()))).toList());
    }

    /** 상벌점 항목 삭제(soft). 과거 부여 이력이 참조한다. */
    @DeleteMapping("/penalty-items/{itemId}")
    public ApiResponse<Void> deleteItem(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long itemId) {
        masterService.deleteItem(me, itemId);
        return ApiResponse.empty();
    }

    // ── 규칙 ──────────────────────────────────────────────────

    /**
     * 트리거별로 고를 수 있는 조건값.
     *
     * <p><b>화면이 드롭다운을 이걸로 채운다.</b> 전에는 조건이 자유 문자열인데 코드표가
     * 없어서, 화면이 값을 추측해 넣을 수밖에 없었다 — 틀리면 <b>영영 걸리지 않는 규칙</b>이
     * 만들어지고 "규칙은 있는데 점수가 안 붙는다" 로만 보인다.
     *
     * <p>값은 엔진이 실제로 비교하는 문자열이라 <b>서버가 유일한 출처</b>다. 화면에 목록을
     * 박아두면 서버가 코드를 바꿀 때 조용히 어긋난다.
     */
    @GetMapping("/penalty-rules/conditions")
    public ApiResponse<List<ConditionGroup>> ruleConditions() {
        return ApiResponse.success(Arrays.stream(PenaltyTriggerType.values())
                .map(t -> new ConditionGroup(t, TriggerCondition.optionsOf(t)))
                .toList());
    }

    /** 꺼진 규칙까지 전부. 화면이 on/off 토글을 그린다. */
    @GetMapping("/penalty-rules")
    public ApiResponse<List<PenaltyRuleRow>> rules(@CurrentAccount AuthPrincipal me,
                                            @RequestParam(required = false) Long academyId,
                                            @RequestParam short year) {
        return ApiResponse.success(masterService.rules(me, academyId, year)
                .stream().map(PenaltyRuleRow::from).toList());
    }

    /** <b>기본은 꺼짐이다</b> — 검증 전 규칙이 전교생에게 벌점을 뿌리지 않게. */
    @PostMapping("/penalty-rules")
    public ApiResponse<PenaltyRuleRow> createRule(@CurrentAccount AuthPrincipal me,
                                           @Valid @RequestBody PenaltyRuleRequest request) {
        return ApiResponse.success(PenaltyRuleRow.from(masterService.createRule(
                me, request.academyId(), request.year(), request.triggerType(),
                request.triggerCondition(), request.penaltyItemId())));
    }

    /** 규칙 수정. */
    @PutMapping("/penalty-rules/{ruleId}")
    public ApiResponse<PenaltyRuleRow> updateRule(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long ruleId,
                                           @Valid @RequestBody PenaltyRuleRequest request) {
        return ApiResponse.success(PenaltyRuleRow.from(masterService.updateRule(
                me, ruleId, request.triggerType(), request.triggerCondition(),
                request.penaltyItemId())));
    }

    /** 켜고 끄기. 지우지 않고 끄면 과거 부여 근거가 남는다. */
    /**
     * ★★ 대체 관계 지정 — 「이 규칙이 부여할 때 ★ 어느 자동 벌점을 먼저 거둘지」
     * (디랩 1029 4-2).
     *
     * <p>★ 쓰임은 하나다 — <b>자동 지각 ↔ 사유 지각·사유 결석 중복 방지.</b>
     * 사유지각은 원장이 {@code LATE} 라 「{@code A} → 자동 지각」 규칙과 ★ 같은 날
     * 겹친다.
     *
     * <p>⚠️ <b>코드가 아니라 데이터</b>로 두는 이유: 디랩이 항목 이름을 바꿔도
     * 안 깨진다.
     *
     * @param supersedesRuleId 거둘 규칙. ★ 비우면 <b>해제</b>한다
     */
    /**
     * ★★ 임계값 — 「몇 번째에 부여하는가」 (디랩 1029 4-6).
     *
     * <p>★ 취침 3회가 그 예다. ⚠️ <b>코드에 3을 박지 않았다</b> — 지점·연도마다
     * 다를 수 있다.
     *
     * @param threshold ★ 비우면 <b>세지 않는다</b>(건별로 걸린다)
     */
    @PatchMapping("/penalty-rules/{ruleId}/threshold")
    public ApiResponse<PenaltyRuleRow> changeThreshold(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long ruleId,
            @RequestParam(required = false) Short threshold) {
        return ApiResponse.success(PenaltyRuleRow.from(
                masterService.changeThreshold(me, ruleId, threshold)));
    }

    @PatchMapping("/penalty-rules/{ruleId}/supersedes")
    public ApiResponse<PenaltyRuleRow> changeSupersedes(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long ruleId,
            @RequestParam(required = false) Long supersedesRuleId) {
        return ApiResponse.success(PenaltyRuleRow.from(
                masterService.changeSupersedes(me, ruleId, supersedesRuleId)));
    }

    @PatchMapping("/penalty-rules/{ruleId}/active")
    public ApiResponse<PenaltyRuleRow> toggleRule(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long ruleId,
                                           @RequestParam boolean active) {
        return ApiResponse.success(PenaltyRuleRow.from(masterService.toggleRule(me, ruleId, active)));
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
                              @NotNull PenaltyCategory category,
                              @Size(max = 100) String groupName,
                              @Size(max = 500) String description) {
        // ★ 수정 시 groupName·description 은 null 과 "" 의 뜻이 다르다 —
        //   null 이면 ★ 기존 값 유지, "" 면 ★ 지운다. 상세는 PenaltyMasterService#updateItem
    }

    public record CopyItemsRequest(@NotNull Long sourceAcademyId,
                                   @NotNull Long targetAcademyId,
                                   @NotNull Short year) {
    }

    /**
     * @param triggerCondition 트리거 조건값. <b>{@code GET /penalty-rules/conditions} 가 주는
     *                         값만 받는다</b> — 목록에 없으면 400 이고, 메시지에 허용값이 붙는다.
     *                         출결이면 태깅 코드(A=지각 등)와 {@code ABSENT}, 루틴이면 결과 상태,
     *                         정기일정이면 {@code NOT_RECOGNIZED}
     */
    public record PenaltyRuleRequest(Long academyId,
                              @NotNull Short year,
                              @NotNull PenaltyTriggerType triggerType,
                              @NotBlank @Size(max = 100) String triggerCondition,
                              @NotNull Long penaltyItemId) {
    }

    /**
     * @param point 벌점은 음수, 상점은 양수다.
     *              <p>★ <b>{@code GET /penalties/items} 와 같은 부호로 내린다.</b> 전에는
     *              이쪽만 저장값을 그대로 줘서, 부호가 잘못 저장된 옛 행에서 두 경로가
     *              다른 값을 보였다 — 화면이 어느 쪽을 믿어야 할지 알 수 없다.
     */
    public record ItemRow(Long id, String itemName, PenaltyCategory category, int point,
                          String groupName, String description, boolean automatic) {

        /** 단건 응답. 방금 만든 항목에는 규칙이 없으므로 한 번만 조회한다. */
        static ItemRow from(PenaltyItem i) {
            return from(i, false);
        }

        /**
         * @param automatic 자동 부여 규칙이 걸려 있나. ★ <b>목록에서는 묶음으로 받은 값을
         *                  넘긴다</b> — 항목마다 조회하면 56개면 57 쿼리다
         */
        static ItemRow from(PenaltyItem i, boolean automatic) {
            int magnitude = Math.abs(i.getPointValue());
            return new ItemRow(i.getId(), i.getItemName(), i.getCategory(),
                    i.getCategory() == PenaltyCategory.DEMERIT ? -magnitude : magnitude,
                    i.getGroupName(), i.getDescription(), automatic);
        }
    }

    /** @param active {@code false}면 만들어만 두고 안 도는 규칙이다 */
    /**
     * @param triggerType 트리거 종류
     * @param conditions  그 트리거에 넣을 수 있는 값. {@code value} 를 그대로 저장한다
     */
    public record ConditionGroup(PenaltyTriggerType triggerType,
                                 List<TriggerCondition.Option> conditions) {
    }

    public record PenaltyRuleRow(Long id, PenaltyTriggerType triggerType, String triggerCondition,
                          Long penaltyItemId, String itemName, int point, boolean active,
                          /**
                           * ★ 이 규칙이 부여할 때 ★ 먼저 거둘 규칙 (1029 4-2).
                           * {@code null} 이면 ★ 거두지 않는다.
                           */
                          Long supersedesRuleId,
                          /**
                           * ★ 몇 번째에 부여하는가 (취침 3회 등).
                           * {@code null} 이면 ★ 세지 않는다.
                           */
                          Short threshold) {

        static PenaltyRuleRow from(PenaltyRule r) {
            return new PenaltyRuleRow(r.getId(), r.getTriggerType(), r.getTriggerCondition(),
                    r.getPenaltyItem().getId(), r.getPenaltyItem().getItemName(),
                    r.getPenaltyItem().getPointValue(), r.isActive(),
                    r.getSupersedesRule() == null ? null : r.getSupersedesRule().getId(),
                    r.getThreshold());
        }
    }
}
