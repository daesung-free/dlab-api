package com.dlab.api.admin.academy;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.kiosk.entity.BranchConfigHistory;
import com.dlab.domain.kiosk.service.BranchConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 지점 설정 관리 (F-4.10-7).
 *
 * <p><b>SUPER_ADMIN 전용이다.</b> 지점 관리자에게 열면 자기 지점 키오스크 자격증명을
 * 스스로 재발급할 수 있는데, 재발급하는 순간 그 지점 키오스크가 전부 인증에 실패한다.
 *
 * <p><b>시크릿 원문은 재발급 응답에만 담긴다.</b> 조회는 언제나 마스킹이다.
 */
@Tag(name = "관리자 · 지점 설정")
@RestController
@RequestMapping("/api/v1/admin/branch-configs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminBranchConfigController {

    private final BranchConfigService branchConfigService;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;

    /** 전 지점. 설정이 없는 지점도 빈 행으로 나온다 — 화면이 9개를 다 그린다. */
    @GetMapping
    public ApiResponse<List<BranchConfigService.View>> list() {
        return ApiResponse.success(branchConfigService.list());
    }

    /**
     * 지점 운영 설정.
     *
     * <p>⚠️ 키오스크 자격증명·PG 코드가 들어 있다 — <b>이 응답을 가입 화면 같은
     * 무인증 경로로 흘리지 말 것.</b>
     */
    @GetMapping("/{academyId}")
    public ApiResponse<BranchConfigService.View> get(@PathVariable Long academyId) {
        return ApiResponse.success(branchConfigService.get(academyId));
    }

    /**
     * 키오스크 자격증명 재발급.
     *
     * <p><b>응답의 {@code secret}은 다시 볼 수 없다.</b> 그리고 재발급 즉시 그 지점
     * 키오스크가 인증에 실패한다 — 키오스크 백엔드 {@code stores} 값을 같이 바꿔야
     * 복구된다. 화면에서 이 경고를 반드시 띄울 것.
     */
    @PostMapping("/{academyId}/kiosk-credential")
    public ApiResponse<BranchConfigService.IssuedCredential> issueKioskCredential(
            @PathVariable Long academyId) {
        return ApiResponse.success(branchConfigService.issueKioskCredential(academyId));
    }

    /** PG 가맹점 코드 변경. 결제가 이 값으로 나가므로 <b>틀리면 그 지점 결제가 통째로 실패한다.</b> */
    @PatchMapping("/{academyId}/pg-merchant-code")
    public ApiResponse<Void> changePgMerchantCode(@PathVariable Long academyId,
                                                  @Valid @RequestBody ValueRequest request) {
        branchConfigService.changePgMerchantCode(academyId, request.value());
        return ApiResponse.empty();
    }

    /** Nebula 장비 ID 변경. 방화벽 해제가 이 장비를 향한다 — 틀리면 다른 지점 와이파이가 열린다. */
    @PatchMapping("/{academyId}/nebula-device-id")
    public ApiResponse<Void> changeNebulaDeviceId(@PathVariable Long academyId,
                                                  @Valid @RequestBody ValueRequest request) {
        branchConfigService.changeNebulaDeviceId(academyId, request.value());
        return ApiResponse.empty();
    }

    /**
     * PG 가맹점 코드 비우기. {@code confirm}에 <b>지금 값을 그대로</b> 넣어야 지워진다 —
     * 비우면 그 지점 결제가 통째로 멈춘다. 이미 비어 있으면 그대로 성공한다.
     */
    @DeleteMapping("/{academyId}/pg-merchant-code")
    public ApiResponse<Void> clearPgMerchantCode(@PathVariable Long academyId,
                                                 @RequestParam(required = false) String confirm) {
        branchConfigService.clearPgMerchantCode(academyId, confirm);
        return ApiResponse.empty();
    }

    /**
     * Nebula 장비 ID 비우기. {@code confirm}에 <b>지금 값을 그대로</b> 넣어야 지워진다 —
     * 비우면 그 지점 와이파이 해제가 멈춘다. 이미 비어 있으면 그대로 성공한다.
     */
    @DeleteMapping("/{academyId}/nebula-device-id")
    public ApiResponse<Void> clearNebulaDeviceId(@PathVariable Long academyId,
                                                 @RequestParam(required = false) String confirm) {
        branchConfigService.clearNebulaDeviceId(academyId, confirm);
        return ApiResponse.empty();
    }

    /** 정책 JSON 교체. 부분 병합이 아니라 통째로 갈아끼운다. */
    @PutMapping("/{academyId}/policy")
    public ApiResponse<Void> replacePolicy(@PathVariable Long academyId,
                                           @RequestBody Map<String, String> policy) {
        branchConfigService.replacePolicy(academyId, policy);
        return ApiResponse.empty();
    }

    /**
     * 변경 이력(감사로그). 값은 남기지 않고 "언제 누가 무엇을"만 남는다.
     *
     * <p><b>사람 이름을 붙여 내린다.</b> 계정 번호만 주면 화면에서 누가 바꿨는지 알 수 없어
     * 이력의 쓸모가 없다. 이름은 <b>조회 시점에</b> 붙인다 — 이력에 박아두면 개인정보가
     * 복제되고, 계정 이름이 바뀌어도 옛 이름이 남는다.
     */
    @GetMapping("/{academyId}/history")
    public ApiResponse<List<HistoryRow>> history(@PathVariable Long academyId) {
        var rows = branchConfigService.history(academyId);
        var names = changerNames(rows);
        return ApiResponse.success(rows.stream()
                .map(h -> HistoryRow.of(h, names.get(h.getCreatedBy()))).toList());
    }

    /** 계정 id → 사람 이름. 행마다 조회하면 화면 한 장에 쿼리가 그만큼 나간다. */
    private Map<Long, String> changerNames(List<BranchConfigHistory> rows) {
        var ids = rows.stream()
                .map(BranchConfigHistory::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !id.equals(
                        com.dlab.common.config.SecurityAuditorAware.SYSTEM_ACCOUNT_ID))
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        for (Object[] row : accountRepository.findActorNames(ids)) {
            if (row[1] != null) {
                names.put((Long) row[0], (String) row[1]);
            }
        }
        return names;
    }

    public record ValueRequest(@NotBlank @Size(max = 100) String value) {
    }

    /**
     * @param changedBy     계정 ID. 배치·시스템 경로면 {@code 0}이다
     * @param changedByName 바꾼 사람 이름. 시스템이거나 계정이 지워졌으면 비어 있다
     */
    public record HistoryRow(Long id, String action, String detail,
                             Long changedBy, String changedByName, Instant changedAt) {

        static HistoryRow of(BranchConfigHistory h, String changedByName) {
            return new HistoryRow(h.getId(), h.getAction().name(), h.getDetail(),
                    h.getCreatedBy(), changedByName, h.getCreatedAt());
        }
    }
}
