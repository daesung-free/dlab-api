package com.dlab.api.admin.access;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.access.entity.DoorCodeRequest;
import com.dlab.domain.access.entity.DoorCodeRequestResult;
import com.dlab.domain.access.service.DoorCodeRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 긴급 출입 요청 내역 (관리자 웹 10번).
 *
 * <h2>★ 학생 화면과 달리 사유가 갈려서 나온다</h2>
 * 학생에게는 전부 「확인할 수 없습니다」지만, <b>전화를 받은 담당자는 원인을 알아야</b>
 * 조치할 수 있다. 특히 {@code NO_CODE_REGISTERED} 는 <b>조치 대상이 학생이 아니라
 * 지점 관리자</b>라 다른 거절과 섞이면 담당자가 학생을 붙잡고 묻게 된다.
 */
@Tag(name = "관리자 · 긴급 출입 내역")
@RestController
@RequestMapping("/api/v1/admin/emergency-access")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminDoorRequestController {

    private final DoorCodeRequestService doorCodeRequestService;

    /** 요청 내역. 지점을 비우면 전 지점(전 지점 권한자만). */
    @GetMapping
    public ApiResponse<List<RequestRow>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.success(
                doorCodeRequestService.history(me, academyId, from, to).stream()
                        .map(RequestRow::from).toList());
    }

    /** 거절 사유 목록 — 화면 필터가 쓴다. 문구를 화면에 중복 정의하지 않게 서버가 내린다. */
    @GetMapping("/results")
    public ApiResponse<List<ResultView>> results() {
        return ApiResponse.success(java.util.Arrays.stream(DoorCodeRequestResult.values())
                .map(r -> new ResultView(r.name(), r.label(), r.needsBranchAction()))
                .toList());
    }

    /**
     * @param studentName  <b>거절 건에서는 빈다</b> — 못 찾았으니 이름도 없다
     * @param phoneMasked  입력한 번호(마스킹). 이름이 빌 때 화면이 이 칸으로 대신 보여준다
     * @param needsBranchAction 지점이 조치해야 하는 건가 — 화면이 색을 달리해 묶는다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "DoorRequestRow")
    public record RequestRow(Long id, Long academyId, String academyName,
                             Instant requestedAt, String studentName, String studentNo,
                             String phoneMasked,
                             DoorCodeRequestResult result, String resultLabel,
                             boolean needsBranchAction) {

        static RequestRow from(DoorCodeRequest r) {
            var enrollment = r.getEnrollment();
            return new RequestRow(r.getId(), r.getAcademy().getId(), r.getAcademy().getAcadNm(),
                    r.getRequestedAt(),
                    enrollment == null ? null : enrollment.getStudentName(),
                    enrollment == null ? null : enrollment.getStudentNo(),
                    r.getPhoneMasked(),
                    r.getResult(), r.getResult().label(),
                    r.getResult().needsBranchAction());
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "DoorRequestResultView")
    public record ResultView(String code, String label, boolean needsBranchAction) {
    }
}
