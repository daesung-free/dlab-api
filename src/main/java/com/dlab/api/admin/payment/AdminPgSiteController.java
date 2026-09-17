package com.dlab.api.admin.payment;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.PgSite;
import com.dlab.domain.payment.service.PgSiteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 사이트코드 관리.
 *
 * <h2>★ 여기가 비어 있으면 결제가 한 건도 되지 않는다</h2>
 * 청구·수납 화면은 다 도는데 링크 생성에서만 {@code PG_SITE_NOT_FOUND} 가 난다.
 * 컷오버 체크리스트 항목이다.
 *
 * <h2>등록 단위는 지점 × 용도 × 채널이다</h2>
 * MID 가 그렇게 갈린다 — 급식비는 <b>급식업체 명의</b>로 결제되므로 학원 코드로 받으면
 * 그 돈이 업체에게 가지 않는다. 지점 칸을 비우면 전 지점 공용이고, 지점별 행이 있으면
 * 그쪽이 우선한다.
 */
@Tag(name = "관리자 · 결제 사이트코드")
@RestController
@RequestMapping("/api/v1/admin/pg-sites")
@RequiredArgsConstructor
public class AdminPgSiteController {

    private final PgSiteService siteService;

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<List<PgSiteView>> list() {
        return ApiResponse.success(siteService.findAll().stream().map(PgSiteView::from).toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<PgSiteView> create(@Valid @RequestBody SavePgSite request) {
        return ApiResponse.success(PgSiteView.from(siteService.create(
                request.academyId(), request.purpose(), request.channel(),
                request.siteCd(), request.mgmtId(), request.displayName(), request.vendorId())));
    }

    /** 수정. 비워 보낸 항목은 바꾸지 않는다. 지점·용도·채널은 바꿀 수 없다 — 그건 다른 칸이다. */
    @PatchMapping("/{siteId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<PgSiteView> update(@PathVariable Long siteId,
                                          @RequestBody UpdatePgSite request) {
        return ApiResponse.success(PgSiteView.from(siteService.update(
                siteId, request.siteCd(), request.mgmtId(),
                request.displayName(), request.vendorId())));
    }

    /** 사용 중지·재개. <b>지우지 않는다</b> — 과거 결제가 어느 가맹점으로 나갔는지가 남아야 한다. */
    @PatchMapping("/{siteId}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<PgSiteView> changeActive(@PathVariable Long siteId,
                                                @RequestParam boolean active) {
        return ApiResponse.success(PgSiteView.from(siteService.changeActive(siteId, active)));
    }

    /**
     * @param academyId 비우면 전 지점 공용
     * @param mgmtId    KCP 상점관리자 계정({@code reg_id}). ★ 바이링크 결제 URL 생성의
     *                  필수 항목이라 비워 두면 그 채널 결제가 거절된다
     * @param vendorId  급식업체 명의 코드면 그 업체. 학원 명의면 비운다
     */
    public record SavePgSite(
            Long academyId,
            @NotNull(message = "용도는 필수입니다.") PgPurpose purpose,
            @NotNull(message = "채널은 필수입니다.") PgChannel channel,
            @NotBlank(message = "사이트코드는 필수입니다.") @Size(max = 10) String siteCd,
            @Size(max = 20) String mgmtId,
            @NotBlank(message = "표시명은 필수입니다.") @Size(max = 100) String displayName,
            Long vendorId) {
    }

    public record UpdatePgSite(@Size(max = 10) String siteCd,
                               @Size(max = 20) String mgmtId,
                               @Size(max = 100) String displayName,
                               Long vendorId) {
    }

    /**
     * @param ready 바로 쓸 수 있는 상태인가. 바이링크인데 상점관리자 계정이 비어 있으면
     *              <b>결제 시점에야</b> 거절되므로 목록에서 미리 보인다
     */
    public record PgSiteView(Long id, Long academyId, String academyName,
                             PgPurpose purpose, PgChannel channel, String siteCd,
                             String mgmtId, String displayName,
                             Long vendorId, String vendorName, boolean active, boolean ready) {

        static PgSiteView from(PgSite s) {
            boolean needsMgmtId = s.getChannel() == PgChannel.BUYLINK;
            return new PgSiteView(s.getId(),
                    s.getAcademy() == null ? null : s.getAcademy().getId(),
                    s.getAcademy() == null ? null : s.getAcademy().getName(),
                    s.getPurpose(), s.getChannel(), s.getSiteCd(), s.getMgmtId(),
                    s.getDisplayName(),
                    s.getVendor() == null ? null : s.getVendor().getId(),
                    s.getVendor() == null ? null : s.getVendor().getName(),
                    s.isActive(),
                    s.isActive() && (!needsMgmtId || (s.getMgmtId() != null && !s.getMgmtId().isBlank())));
        }
    }
}
