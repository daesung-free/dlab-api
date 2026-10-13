package com.dlab.api.admin.access;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.access.entity.MorningAttendanceCheck;
import com.dlab.domain.access.service.MorningAttendanceCheckService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 오전 출근 확인 내역 (긴급출입 기능 B · 관리자 웹 11번).
 *
 * <p>매일 07:05 배치가 남긴 검사 결과를 본다. <b>발송 여부가 함께 나온다</b> —
 * 「발송했는데 안 왔다」와 「검사가 안 돌았다」를 가려야 하기 때문이다.
 */
@Tag(name = "관리자 · 오전 출근 확인")
@RestController
@RequestMapping("/api/v1/admin/staff-attendance/checks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminMorningCheckController {

    private final MorningAttendanceCheckService checkService;

    /**
     * 검사 내역.
     *
     * <p>지점 범위는 계정이 정한다 — <b>전 지점 권한자는 전 지점</b>을 한 번에 본다
     * (본사가 아침에 한눈에 보는 화면이다). 지점 관리자는 자기 지점만 나온다.
     *
     * @param from 비우면 최근 2주
     */
    @GetMapping
    public ApiResponse<List<CheckView>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusWeeks(2) : from;
        return ApiResponse.success(checkService.search(me, start, end).stream()
                .map(CheckView::from)
                .toList());
    }

    /**
     * @param inCount        그 시각까지 들어온 출근 건수. <b>0이면 미확인</b>이다
     * @param alertNeeded    알림 대상이었는가(= {@code inCount == 0})
     * @param alertSent      실제로 보냈는가. ⚠️ <b>알리고 연동 전에는 늘 {@code false}</b>다 —
     *                       화면은 그때 「미연동」으로 그려야 「발송 실패」와 섞이지 않는다
     * @param recipientCount 보낼 대상 계정 수. ★ <b>{@code 0}이면 권한을 켜 둔 계정이 없다는
     *                       뜻</b>이고 그건 설정 문제다 — 화면이 드러내야 한다
     */
    public record CheckView(Long id, Long academyId, String academyName,
                            LocalDate checkDate, Instant checkedAt,
                            int inCount, boolean alertNeeded, boolean alertSent,
                            int recipientCount) {

        static CheckView from(MorningAttendanceCheck c) {
            return new CheckView(c.getId(), c.getAcademy().getId(), c.getAcademy().getName(),
                    c.getCheckDate(), c.getCheckedAt(),
                    c.getInCount(), c.isAlertNeeded(), c.isAlertSent(), c.getRecipientCount());
        }
    }
}
