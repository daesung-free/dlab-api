package com.dlab.domain.survey.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyParticipant;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.repository.AccountRepository;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 알림 (F-4.6 "미응답자 독려").
 *
 * <h2>★★ 문자는 건당 과금이다 — 보내기 전에 몇 건인지 셀 수 있어야 한다</h2>
 * 전 지점 설문이면 <b>수천 건</b>이고, 되돌릴 수 없다. 그래서 {@link #preview} 를 따로 둔다 —
 * 화면이 「○○명에게 보냅니다」를 확인받은 뒤 발송을 누르는 흐름을 전제한다.
 *
 * <h2>★★ 대상자 행이 없는 설문에는 독촉을 보낼 수 없다</h2>
 * 범위(전 지점·지점·반)로 돌리는 일반 설문은 <b>{@code SurveyParticipant} 행을 미리 만들지
 * 않는다</b>({@code assignTargets} 를 타는 장학 동의서만 만든다). 그래서 「누가 아직 안 냈는지」를
 * 알 수 없다.
 *
 * <p>⚠️ <b>그때 0건을 보내고 성공을 돌려주면 안 된다.</b> 데스크는 「독촉을 보냈다」고 알고
 * 끝내는데 <b>아무에게도 가지 않았다.</b> 명시적으로 거절해 이유를 알려준다.
 *
 * <h2>★ 알림이 실패해도 본 행위는 되돌리지 않는다</h2>
 * {@code REQUIRES_NEW} 로 떼고 예외를 삼킨다 — 출결 알림과 같은 판단이다. 특히
 * {@code NotificationService#send} 는 템플릿 행이 없으면 예외를 던지므로, 같은 트랜잭션에
 * 두면 마이그레이션이 덜 적용된 환경에서 <b>대상자 지정 자체가 실패</b>한다.
 *
 * <h2>⚠️ 지금은 실제로 나가지 않는다</h2>
 * 템플릿의 {@code content_confirmed} 가 {@code FALSE} 다 — <b>디랩에서 문구를 받지 못했다.</b>
 * 그래서 발송 이력에 {@code SKIPPED("문구 미확정")} 으로만 남는다. 경로는 완성이고
 * <b>문구가 오면 템플릿 행만 고치면 돈다.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyNotifier {

    private static final DateTimeFormatter CLOSES_AT_FORMAT =
            DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final NotificationService notificationService;
    private final AccountRepository accountRepository;
    private final Clock clock;

    /**
     * 대상자로 지정된 학생에게 첫 발송. <b>실패해도 호출부에 전파하지 않는다.</b>
     *
     * @param participants {@code assignTargets} 가 <b>이번에 새로 만든</b> 행만. 이미 대상이던
     *                     학생에게 다시 보내면 같은 설문 안내를 두 번 받는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyAssigned(Survey survey, List<SurveyParticipant> participants) {
        for (SurveyParticipant participant : participants) {
            send(NotificationEvent.SURVEY_ASSIGNED, survey, participant,
                    // 한 설문당 한 번 — 날짜를 넣지 않는다
                    "%s:%d:%d".formatted(NotificationEvent.SURVEY_ASSIGNED,
                            survey.getId(), participant.getId()));
        }
    }

    /**
     * 미응답자 독촉.
     *
     * <p>★ <b>중복 방지 키에 날짜를 넣는다.</b> 독촉은 며칠에 걸쳐 여러 번 보내는 것이
     * 정상이지만, <b>같은 날 두 번 누르면 두 번 가면 안 된다.</b> 출결 알림이 태깅 로그 id 를
     * 쓰는 것과 반대 방향의 판단이다 — 거기는 하루에 여러 번이 정상이었다.
     *
     * @return 실제로 발송을 시도한 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int notifyPending(Survey survey, List<SurveyParticipant> participants) {
        String today = java.time.LocalDate.now(clock).toString();
        int attempted = 0;
        for (SurveyParticipant participant : participants) {
            if (participant.getSubmittedAt() != null) {
                continue;
            }
            send(NotificationEvent.SURVEY_REMINDER, survey, participant,
                    "%s:%d:%d:%s".formatted(NotificationEvent.SURVEY_REMINDER,
                            survey.getId(), participant.getId(), today));
            attempted++;
        }
        log.info("설문 독촉: surveyId={}, 대상={}건", survey.getId(), attempted);
        return attempted;
    }

    /**
     * 발송 전 대상 집계 — <b>화면이 확인 모달에 쓴다.</b>
     *
     * <p>★ 문자는 건당 과금이고 되돌릴 수 없다. 「몇 명에게 가는가」를 <b>누르기 전에</b>
     * 보여주지 않으면, 전 지점 설문에서 수천 건이 한 번에 나간다.
     */
    public Preview preview(List<SurveyParticipant> participants) {
        int total = participants.size();
        int pending = (int) participants.stream()
                .filter(p -> p.getSubmittedAt() == null).count();
        return new Preview(total, total - pending, pending);
    }

    /**
     * @param targetCount    대상자 행 수. <b>0 이면 범위로 돌리는 설문</b>이라 독촉을 보낼 수 없다
     * @param pendingCount   미응답 수 — <b>독촉이 실제로 나갈 건수</b>다
     */
    public record Preview(int targetCount, int submittedCount, int pendingCount) {

        /** 독촉을 보낼 수 있는가. 대상자 행이 없으면 「누가 안 냈는지」를 알 수 없다. */
        public boolean remindable() {
            return targetCount > 0;
        }
    }

    /** 대상자 행이 없으면 거절한다 — 0건을 보내고 성공을 돌려주면 아무도 모른다. */
    public void requireRemindable(Preview preview) {
        if (!preview.remindable()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이 설문은 대상자를 지정하지 않아 미응답자를 알 수 없습니다. "
                            + "범위로 돌리는 설문은 누가 응답하지 않았는지 서버가 알 수 없습니다.");
        }
    }

    private void send(NotificationEvent event, Survey survey, SurveyParticipant participant,
                      String dedupKey) {
        try {
            var enrollment = participant.getEnrollment();
            Account account = accountRepository
                    .findByStudentId(enrollment.getStudent().getId())
                    .orElse(null);
            if (account == null) {
                // ⚠️ 앱 가입을 안 한 학생이다. 조용히 넘기지 않고 남긴다 —
                //    「보냈는데 안 왔다」는 문의가 들어오면 여기가 답이다
                log.warn("설문 알림 대상에 앱 계정이 없다: surveyId={}, enrollmentId={}",
                        survey.getId(), enrollment.getId());
                return;
            }

            Map<String, String> variables = new java.util.HashMap<>();
            variables.put("surveyTitle", survey.getTitle());
            variables.put("closesAt", survey.getClosesAt() == null ? "기한 없음"
                    : CLOSES_AT_FORMAT.format(survey.getClosesAt().atZone(clock.getZone())));

            notificationService.send(NotificationCommand.forStudent(
                    event, account, enrollment.getStudent(), enrollment.getAcademy(),
                    enrollment.getYear(), variables, dedupKey));
        } catch (Exception e) {
            // ★ 삼킨다. 대상자 지정·독촉은 이미 끝났고 알림이 그것을 되돌리면 안 된다
            log.error("설문 알림 발송 실패: event={}, surveyId={}, participantId={}",
                    event, survey.getId(), participant.getId(), e);
        }
    }

    /** 대상자 목록에서 미응답만. */
    public static List<SurveyParticipant> pendingOf(List<SurveyParticipant> participants) {
        List<SurveyParticipant> pending = new ArrayList<>();
        for (SurveyParticipant p : participants) {
            if (p.getSubmittedAt() == null) {
                pending.add(p);
            }
        }
        return pending;
    }
}
