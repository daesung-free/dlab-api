package com.dlab.api.admin.academy;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.service.AcademyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지점 관리.
 *
 * <p><b>등록 엔드포인트가 없다.</b> 지점은 9개로 고정이고 {@code acad_cd}는 대성전산이
 * 부여한 값이라 새로 만들 일이 없다 — 최초 심기는 마이그레이션이 한다.
 *
 * <p>목록은 여러 화면(교시·공지·좌석·공휴일)이 "지점 고르기"에 쓴다.
 */
@RestController
@RequestMapping("/api/v1/admin/academies")
@RequiredArgsConstructor
public class AdminAcademyController {

    private final AcademyService academyService;

    /** @param includeInactive 비활성 지점 포함. 기본은 활성만 — 셀렉트에 죽은 지점이 뜨면 안 된다 */
    @GetMapping
    public ApiResponse<List<AcademyResponse>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(academyService.findAll(me, includeInactive).stream()
                .map(AcademyResponse::from).toList());
    }

    @GetMapping("/{academyId}")
    public ApiResponse<AcademyResponse> detail(@CurrentAccount AuthPrincipal me,
                                               @PathVariable Long academyId) {
        return ApiResponse.success(AcademyResponse.from(academyService.findOne(me, academyId)));
    }

    /**
     * 기본정보 수정.
     *
     * <p><b>지점코드·연동코드는 바꿀 수 없다</b> — 키오스크가 그 값으로 인증·매칭한다.
     */
    @PutMapping("/{academyId}")
    public ApiResponse<AcademyResponse> update(@CurrentAccount AuthPrincipal me,
                                               @PathVariable Long academyId,
                                               @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.success(AcademyResponse.from(academyService.update(
                me, academyId, request.acadNm(), request.fullNm(),
                request.attendanceDeadline())));
    }

    /**
     * 활성·비활성 — <b>본사만.</b>
     *
     * <p>끄면 그 지점 키오스크 토큰 발급부터 막혀 태깅이 전면 중단된다.
     */
    @PutMapping("/{academyId}/active")
    public ApiResponse<AcademyResponse> changeActive(@CurrentAccount AuthPrincipal me,
                                                     @PathVariable Long academyId,
                                                     @RequestParam boolean active) {
        return ApiResponse.success(
                AcademyResponse.from(academyService.changeActive(me, academyId, active)));
    }

    /** @param attendanceDeadline 등원 기준 시각. 이 시각 이후 첫 태깅이 지각이다 */
    public record UpdateRequest(
            @NotBlank(message = "지점명은 필수입니다.") @Size(max = 100) String acadNm,
            @Size(max = 100) String fullNm,
            @NotNull(message = "등원 기준 시각은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime attendanceDeadline) {
    }

    /**
     * @param acadCd    대성전산 부여 코드. <b>수정 불가</b>
     * @param storeCode 키오스크 매칭 코드. <b>수정 불가</b>
     */
    public record AcademyResponse(
            Long id,
            String acadCd,
            String acadNm,
            String fullNm,
            String storeCode,
            LocalTime attendanceDeadline,
            boolean active) {

        public static AcademyResponse from(Academy a) {
            return new AcademyResponse(a.getId(), a.getAcadCd(), a.getAcadNm(),
                    a.getFullNmOrFallback(), a.getStoreCode(),
                    a.getAttendanceDeadline(), a.isActive());
        }
    }
}
