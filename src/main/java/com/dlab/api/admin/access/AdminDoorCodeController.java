package com.dlab.api.admin.access;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.access.entity.BranchDoorCode;
import com.dlab.domain.access.service.DoorCodeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 지점 도어락 번호 (긴급출입 1-5 · 관리자 웹 8번).
 *
 * <p>지점 관리자가 08:00 이후 실물 도어락을 교체하고 그 번호를 여기에 넣는다.
 * 06:59~08:00 사이 학생이 출입문 QR 로 이 번호를 받아 간다.
 *
 * <h2>★ 번호를 돌려주지 않는다</h2>
 * 조회·이력에 평문을 싣지 않는다. 관리자는 실물 도어락을 만지는 사람이라 지금 번호를
 * 알고 있고, 화면에 띄우면 <b>어깨 너머로 새는 경로가 하나 늘어난다.</b>
 * 언제 누가 바꿨는지만 본다.
 */
@Tag(name = "관리자 · 도어락 번호")
@RestController
@RequestMapping("/api/v1/admin/door-codes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminDoorCodeController {

    private final DoorCodeService doorCodeService;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;

    /**
     * 번호 등록·교체.
     *
     * <p>★ <b>이전에 쓴 번호면 거절한다.</b> 돌려 쓰면 지난번에 번호를 받아 간 학생이
     * 지금도 들어온다. 공백·하이픈은 흘려보내고 숫자만 본다.
     *
     * @param academyId 비우면 내 지점. 본사는 지정해야 한다
     */
    @PostMapping
    public ApiResponse<DoorCodeView> register(@CurrentAccount AuthPrincipal me,
                                              @RequestParam(required = false) Long academyId,
                                              @Valid @RequestBody CodeRequest request) {
        BranchDoorCode saved = doorCodeService.register(me, academyId, request.code());
        return ApiResponse.success(DoorCodeView.of(saved, actorNames(List.of(saved))));
    }

    /**
     * 지금 번호의 <b>상태</b>. 번호 자체는 내려가지 않는다.
     *
     * <p>{@code appliedAt} 이 오래됐으면 교체를 안 한 것이다 — 화면이 그것으로
     * 「교체가 필요합니다」를 띄울 수 있다.
     */
    @GetMapping("/current")
    public ApiResponse<DoorCodeView> current(@CurrentAccount AuthPrincipal me,
                                             @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(doorCodeService.current(me, academyId)
                .map(code -> DoorCodeView.of(code, actorNames(List.of(code))))
                .orElse(null));
    }

    /** 교체 이력. 언제 누가 바꿨는지만 나온다. */
    @GetMapping("/history")
    public ApiResponse<List<DoorCodeView>> history(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Long academyId) {
        List<BranchDoorCode> history = doorCodeService.history(me, academyId);
        var names = actorNames(history);
        return ApiResponse.success(history.stream()
                .map(code -> DoorCodeView.of(code, names))
                .toList());
    }

    /**
     * 바꾼 사람 이름 — <b>한 번에</b> 받아 붙인다.
     *
     * <p>이력 50줄에 계정마다 조회하면 쿼리가 50번 나간다(감사 로그와 같은 방식).
     * <b>시스템 계정({@code 0})은 조회하지 않는다</b> — 그 id 의 계정은 없고,
     * 이름이 비어 있는 것이 곧 "사람이 아니다"라는 뜻이다.
     */
    private java.util.Map<Long, String> actorNames(List<BranchDoorCode> codes) {
        var ids = codes.stream()
                .map(BranchDoorCode::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !id.equals(
                        com.dlab.common.config.SecurityAuditorAware.SYSTEM_ACCOUNT_ID))
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (Object[] row : accountRepository.findActorNames(ids)) {
            if (row[1] != null) {
                names.put((Long) row[0], (String) row[1]);
            }
        }
        return names;
    }

    /** @param code 숫자 4~12자리. 공백·하이픈이 섞여도 된다 */
    public record CodeRequest(@NotBlank @Size(max = 20) String code) {
    }

    /**
     * @param changedBy     바꾼 계정 id. 배치·시스템이면 {@code 0} 이다
     * @param changedByName 바꾼 사람 이름. <b>시스템이거나 계정이 지워졌으면 비어 있다</b> —
     *                      화면은 그때 「시스템」이나 「-」로 그리면 된다.
     *                      이름을 함께 내리는 이유는 <b>이력 화면을 지점 관리자도 보는데
     *                      그 계정에는 계정 목록 조회 권한이 없을 수 있어서</b>다
     */
    public record DoorCodeView(Long id, Long academyId, Instant appliedAt,
                               Long changedBy, String changedByName) {

        static DoorCodeView of(BranchDoorCode c, java.util.Map<Long, String> names) {
            return new DoorCodeView(c.getId(), c.getAcademy().getId(),
                    c.getAppliedAt(), c.getCreatedBy(),
                    c.getCreatedBy() == null ? null : names.get(c.getCreatedBy()));
        }
    }
}
