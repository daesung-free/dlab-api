package com.dlab.api.admin.attendance;

import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService.LeaveRow;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService.Status;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 웹 좌석 이탈 현황 (F-4.3-2).
 *
 * <p>조회만 있다 — 이탈·복귀는 키오스크 태깅으로만 생긴다.
 */
@Tag(name = "관리자 · 좌석 이탈 (F-4.3-2)")
@RestController
@RequestMapping("/api/v1/admin/seat-leaves")
@RequiredArgsConstructor
public class AdminSeatLeaveController {

    private final SeatLeaveBoardService boardService;

    /**
     * 이탈 이력. 이탈 한 건이 한 행이고 복귀 시각·이탈 시간이 붙는다. 최근 건이 위로 온다.
     *
     * @param academyId 비우면 내 지점. 전 지점 권한자는 지정해야 한다
     * @param date      하루 조회. {@code from}·{@code to}를 주면 그쪽이 우선한다. 둘 다 없으면 오늘
     * @param to        기간 끝(포함). 최대 {@value SeatLeaveBoardService#MAX_RANGE_DAYS}일
     * @param statuses  상태 필터
     * @param keyword   이름·학번·좌석 통합 검색
     */
    @GetMapping
    public ApiResponse<SeatLeaveBoardResponse> history(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) List<Status> statuses,
            @RequestParam(required = false) String keyword) {

        LocalDate day = date == null ? LocalDate.now() : date;
        List<LeaveRow> rows = (from != null && to != null)
                ? boardService.history(me, academyId, from, to, classId)
                : boardService.history(me, academyId, day, day, classId);

        List<LeaveRow> filtered = rows.stream()
                .filter(r -> statuses == null || statuses.isEmpty() || statuses.contains(r.status()))
                .filter(r -> matchesKeyword(r, keyword))
                .toList();

        // ★ 검색은 원본으로 하고 마스킹은 마지막에 한다 — 먼저 가리면 이름 검색이 안 된다
        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(new SeatLeaveBoardResponse(
                filtered.stream().map(r -> LeaveRowResponse.of(r, raw)).toList(),
                summary(filtered),
                !raw));
    }

    /** 지금 이탈 중인 학생. 오래 나가 있는 학생이 위로 온다. */
    @GetMapping("/current")
    public ApiResponse<List<LeaveRowResponse>> current(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Long classId) {
        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(boardService.current(me, academyId, classId).stream()
                .map(r -> LeaveRowResponse.of(r, raw))
                .toList());
    }

    private boolean matchesKeyword(LeaveRow r, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim();
        return contains(r.name(), kw) || contains(r.studentNo(), kw) || contains(r.seatCd(), kw);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    /** 화면 상단 통계. 조회된 목록 기준이라 필터를 걸면 같이 줄어든다. */
    private Map<String, Long> summary(List<LeaveRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", (long) rows.size());
        for (Status s : Status.values()) {
            counts.put(s.name(), rows.stream().filter(r -> r.status() == s).count());
        }
        return counts;
    }

    /**
     * @param masked 이름이 가려졌는지. 화면이 모르면 또 가려 이름이 통째로 사라진다
     */
    public record SeatLeaveBoardResponse(List<LeaveRowResponse> rows, Map<String, Long> summary,
                                         boolean masked) {
    }

    /**
     * @param name   상위 관리자가 아니면 가려진 값이다. 학생을 못 찾은 건은 비어 있다
     * @param masked 이 행의 이름이 가려졌는지
     */
    public record LeaveRowResponse(Long leaveLogId, Long enrollmentId, String studentNo,
                                   String name, String className, String areaCd, String seatCd,
                                   Instant leftAt, Instant closedAt, Status status, Long minutes,
                                   boolean resolved, boolean masked) {

        static LeaveRowResponse of(LeaveRow r, boolean raw) {
            return new LeaveRowResponse(r.leaveLogId(), r.enrollmentId(), r.studentNo(),
                    raw ? r.name() : Masking.name(r.name()),
                    r.className(), r.areaCd(), r.seatCd(), r.leftAt(), r.closedAt(),
                    r.status(), r.minutes(), r.resolved(), !raw);
        }
    }
}
