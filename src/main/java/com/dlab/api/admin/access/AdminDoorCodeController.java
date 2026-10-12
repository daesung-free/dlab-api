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
        return ApiResponse.success(DoorCodeView.from(
                doorCodeService.register(me, academyId, request.code())));
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
                .map(DoorCodeView::from)
                .orElse(null));
    }

    /** 교체 이력. 언제 누가 바꿨는지만 나온다. */
    @GetMapping("/history")
    public ApiResponse<List<DoorCodeView>> history(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(doorCodeService.history(me, academyId).stream()
                .map(DoorCodeView::from)
                .toList());
    }

    /** @param code 숫자 4~12자리. 공백·하이픈이 섞여도 된다 */
    public record CodeRequest(@NotBlank @Size(max = 20) String code) {
    }

    /**
     * @param changedBy 바꾼 계정. 배치·시스템이면 {@code 0} 이다
     */
    public record DoorCodeView(Long id, Long academyId, Instant appliedAt, Long changedBy) {

        static DoorCodeView from(BranchDoorCode c) {
            return new DoorCodeView(c.getId(), c.getAcademy().getId(),
                    c.getAppliedAt(), c.getCreatedBy());
        }
    }
}
