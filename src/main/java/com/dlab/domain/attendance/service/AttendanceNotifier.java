package com.dlab.domain.attendance.service;

import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출결 태깅 알림(디랩 1016 문구 1~5번). 태깅 원장에 남은 이벤트를 학부모에게 알린다.
 *
 * <h2>★★ 태깅과 분리한다 — 알림이 못 나가도 등원 사실은 남아야 한다</h2>
 * {@code REQUIRES_NEW}로 떼고 예외를 삼킨다. 상벌점 엔진과 같은 판단이다(§7).
 * 특히 <b>{@link NotificationService#send}는 템플릿 행이 없으면 예외를 던진다</b> —
 * 같은 트랜잭션에 두면 마이그레이션이 덜 적용된 환경에서 <b>태깅 자체가 전부 실패</b>하고,
 * 키오스크 화면에는 「서버 오류」만 떠서 원인이 어디에도 안 보인다.
 *
 * <h2>★ 보내는 쪽은 원장 이벤트다</h2>
 * 키오스크 응답값({@code reportedAs})이 아니라 원장에 남은 값을 쓴다. 사유지각은 원장이
 * 지각이고 화면엔 등원으로 뜨는데, <b>둘 다 등원 알림으로 묶이므로</b> 결과가 같다.
 * 자동 상벌점도 원장 기준이라 판단 기준이 한 곳으로 모인다.
 *
 * <h2>★ 중복 방지는 원장 행 단위다</h2>
 * {@code dedupKey}에 <b>태깅 로그 id</b>를 쓴다. 날짜로 묶으면 <b>하루에 여러 번이 정상인
 * 외출·복귀가 두 번째부터 안 나간다</b>. 수신자가 여럿이라 계정 id까지 넣는다
 * ({@code dedup_key}는 전역 유니크다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceNotifier {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationService notificationService;
    private final com.dlab.domain.notification.service.GuardianRecipientResolver
            recipientResolver;
    private final java.time.Clock clock;

    /**
     * 태깅 1건에 대한 알림. <b>실패해도 호출부에 전파하지 않는다.</b>
     *
     * @param tagging 저장이 끝난 원장 행 — id가 중복 방지 키에 들어가므로 저장 전에 부르면 안 된다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyTagged(StudentEnrollment enrollment, AttendanceTaggingLog tagging) {
        NotificationEvent event = eventOf(tagging.getEventType());
        if (event == null) {
            return;
        }
        try {
            // 학부모에게만 보낸다 — 학생 본인에게 "당신이 등원했습니다"는 보낼 이유가 없다.
            // 부·모 양쪽에 각각 간다(선착순 승인과 같은 전제).
            // ★★ 앱을 설치하지 않은 보호자에게도 보낸다 — 수신자 해석은
            //    GuardianRecipientResolver 한 곳이다(미등원 배치·수동 발송과 같은 판정).
            Map<String, String> variables = Map.of("taggedAt",
                    TIME_FORMAT.format(tagging.getRecordedAt().atZone(clock.getZone())));

            for (var recipient : recipientResolver.of(enrollment.getStudent().getId())) {
                if (!recipient.hasPhone()) {
                    continue;
                }
                notificationService.send(recipient.account() != null
                        ? NotificationCommand.forStudent(
                        event,
                        recipient.account(),
                        enrollment.getStudent(),
                        enrollment.getAcademy(),
                        enrollment.getYear(),
                        variables,
                        dedupKey(event, tagging, recipient))
                        : NotificationCommand.forStudentPhone(
                        event,
                        recipient.phone(),
                        recipient.name(),
                        enrollment.getStudent(),
                        enrollment.getAcademy(),
                        enrollment.getYear(),
                        variables,
                        dedupKey(event, tagging, recipient)));
            }
        } catch (Exception e) {
            // ★ 삼킨다. 태깅은 이미 끝났고, 알림이 본 행위를 되돌리면 안 된다.
            //   대신 error 로 남긴다 — 조용히 넘기면 학부모가 못 받은 것을 아무도 모른다
            log.error("출결 알림 발송 실패: event={}, enrollmentId={}",
                    event, enrollment.getId(), e);
        }
    }

    /**
     * 중복 방지 키.
     *
     * <p>★ <b>보호자 id 를 쓴다.</b> 계정 id 로 두면 계정이 없는 보호자(앱 미설치)의
     * 멱등성이 성립하지 않는다 — 같은 태깅에 두 번 나갈 수 있다.
     */
    private String dedupKey(NotificationEvent event, AttendanceTaggingLog tagging,
                            com.dlab.domain.notification.service.GuardianRecipientResolver
                                    .GuardianRecipient recipient) {
        return "%s:%d:%d".formatted(event, tagging.getId(), recipient.guardianId());
    }

    /**
     * 태깅 이벤트 → 알림 이벤트.
     *
     * <p>★ <b>지각은 따로 나간다</b>(디랩 1019). 1016 에는 등원에 합쳐 두었다 —
     * 지각 전용 문구가 없는데 빼면 기준시각에 미등원 알림을 받은 학부모가 자녀를
     * <b>결석으로 알고 끝내기</b> 때문이었다. 이제 전용 문구가 있으므로 그 전제가 사라졌다.
     *
     * <p>⚠️ <b>사유지각도 지각 알림이 나간다.</b> 원장이 지각이고(화면만 등원으로 뜬다)
     * 1019 문구에 조건이 없다 — 학부모가 승인한 건에도 「지각 등원」이 간다.
     * 가르게 되면 {@code case LATE} 를 사유 여부로 나누면 된다.
     *
     * <p>★ <b>사유 외출도 외출이다.</b> 사유 제출 여부는 벌점·집계의 구분이고, 학부모에게는
     * 「나갔다」는 사실이 같다.
     */
    private static NotificationEvent eventOf(AttendanceEventType type) {
        return switch (type) {
            case CHECK_IN -> NotificationEvent.ATTENDANCE_CHECK_IN;
            case LATE -> NotificationEvent.ATTENDANCE_LATE;
            case CHECK_OUT -> NotificationEvent.ATTENDANCE_CHECK_OUT;
            case OUTING, EXCUSED_OUTING -> NotificationEvent.ATTENDANCE_OUTING;
            case RETURN -> NotificationEvent.ATTENDANCE_RETURN;
            case EARLY_LEAVE -> NotificationEvent.ATTENDANCE_EARLY_LEAVE;
        };
    }
}
