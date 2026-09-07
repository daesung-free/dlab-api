package com.dlab.api.admin.notification;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationStatus;
import com.dlab.domain.notification.repository.NotificationLogRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 발송 이력 (F-4.4 문자 발송 · F-C-6 앱 운영).
 *
 * <p><b>채널을 나누지 않는다.</b> 알림톡과 푸시를 따로 두면 "이 학생에게 언제 무엇이
 * 갔나"를 한 번에 못 본다 — 한 테이블에 채널 컬럼으로 쌓고 필터로 가른다.
 *
 * <p>⚠️ <b>지금은 대부분 {@code SKIPPED}다.</b> 문구 확정(I-4)·카카오 심사(E-5)·
 * FCM 자격증명(E-7)이 모두 대기라 실제 발송이 일어나지 않고, 이력만 남는다.
 * 화면이 "0건 발송"을 오류로 보이지 않게 상태별 수를 함께 내린다.
 */
@Tag(name = "관리자 · 발송 이력 (F-4.4)")
@RestController
@RequestMapping("/api/v1/admin/notification-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminNotificationLogController {

    private final NotificationLogRepository logRepository;
    private final Clock clock;

    /**
     * 발송 이력 목록.
     *
     * <p>기본이 <b>최근 7일</b>이다 — 기간을 안 주면 전 기간을 훑게 되어 화면이 안 뜬다.
     *
     * @param studentId 사람(student) id다 — 등록 건 id가 아니다. 알림은 "누구 얘기인지"가
     *                  중요하지 기수가 중요하지 않아 사람에 붙어 있다
     */
    @GetMapping
    public ApiResponse<List<LogResponse>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) NotificationEvent event,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Long studentId,
            @PageableDefault(size = 50) Pageable pageable) {

        LocalDate today = LocalDate.now(clock);
        LocalDate start = from == null ? today.minusDays(6) : from;
        LocalDate end = to == null ? today : to;

        var page = logRepository.search(
                me.resolveAcademyScope(academyId), event, channel, status, studentId,
                start.atStartOfDay(clock.getZone()).toInstant(),
                // 끝 날짜를 포함해야 한다 — 오늘 나간 알림이 오늘 조회에서 빠지면 안 된다
                end.plusDays(1).atStartOfDay(clock.getZone()).toInstant(),
                pageable);

        return ApiResponse.from(page.map(LogResponse::from));
    }

    /**
     * 발송 묶음 집계 — "어제 미등원 알림이 몇 명에게 나갔나".
     *
     * <p><b>일자 × 이벤트 × 채널</b>로 묶는다. 자동발송은 배치 한 번에 수백 건이 나가는데
     * 그 실행을 묶는 키가 따로 없어, 실무에서 세는 단위가 이것이다.
     *
     * <p>알림톡은 <b>건당 과금</b>이라 {@code sent}가 정산 근거다 — {@code total}은
     * 시도한 수라 실패·건너뜀이 섞여 있다.
     */
    @GetMapping("/summary")
    public ApiResponse<List<SummaryRow>> summary(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate today = LocalDate.now(clock);
        LocalDate start = from == null ? today.minusDays(29) : from;
        LocalDate end = to == null ? today : to;

        return ApiResponse.success(logRepository.summarize(
                        me.resolveAcademyScope(academyId),
                        start.atStartOfDay(clock.getZone()).toInstant(),
                        end.plusDays(1).atStartOfDay(clock.getZone()).toInstant())
                .stream().map(SummaryRow::from).toList());
    }

    /**
     * @param sentAt     실제 나간 시각. <b>실패·건너뜀이면 비어 있다</b> — 그래도 이력이라
     *                   목록에는 남는다("왜 안 왔지"에 답해야 한다)
     * @param failReason {@code SKIPPED}면 왜 안 보냈는지가 여기 있다(문구 미확정 등)
     */
    public record LogResponse(Long id, Long academyId, NotificationEvent event,
                              NotificationChannel channel, NotificationStatus status,
                              Long studentId, String studentName, String title, String body,
                              String failReason, Instant sentAt, Instant createdAt) {

        static LogResponse from(NotificationLog l) {
            return new LogResponse(l.getId(),
                    l.getAcademy() == null ? null : l.getAcademy().getId(),
                    l.getEventCode(), l.getChannel(), l.getStatus(),
                    l.getStudent() == null ? null : l.getStudent().getId(),
                    l.getStudent() == null ? null : l.getStudent().getName(),
                    l.getTitle(), l.getBody(), l.getFailReason(),
                    l.getSentAt(), l.getCreatedAt());
        }
    }

    /**
     * @param total 시도한 수. 실패·건너뜀이 섞여 있다
     * @param sent  실제로 나간 수. <b>알림톡 정산 근거가 이 값</b>이다
     */
    public record SummaryRow(LocalDate date, NotificationEvent event, NotificationChannel channel,
                             long total, long sent, long failed, long skipped, Instant firstAt) {

        static SummaryRow from(Object[] row) {
            return new SummaryRow(
                    ((java.sql.Date) row[0]).toLocalDate(),
                    (NotificationEvent) row[1],
                    (NotificationChannel) row[2],
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    ((Number) row[5]).longValue(),
                    ((Number) row[6]).longValue(),
                    (Instant) row[7]);
        }
    }
}
