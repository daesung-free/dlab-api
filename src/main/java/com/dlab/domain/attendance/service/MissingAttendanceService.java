package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 미등원(무단결석) 감지.
 *
 * <p>다른 로직은 전부 "키오스크 이벤트가 발생하면 반응"하는 구조인데, 이건 반대로
 * <b>이벤트가 없었음을 감지</b>해야 하는 유일한 유형이다. 그래서 태깅 수신만으로는 구현할 수
 * 없고 등원 기준시각에 도는 배치가 필요하다.
 *
 * <p>대상: 등원 기준시각까지 태깅 기록이 없고, 사전에 사유도 제출하지 않은 학생.
 * 사유를 낸 학생은 무단결석이 아니므로 제외한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingAttendanceService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("M월 d일").withZone(TimeConfig.KST);

    private final StudentEnrollmentRepository enrollmentRepository;
    private final com.dlab.domain.notification.service.GuardianRecipientResolver
            recipientResolver;
    private final NotificationService notificationService;

    /**
     * 한 지점의 미등원 학생을 찾아 학부모에게 알린다.
     *
     * @return 알림 대상 학생 수
     */
    @Transactional
    public int detectAndNotify(Academy academy, LocalDate date) {
        List<StudentEnrollment> absentees = enrollmentRepository.findUnexcusedAbsentees(academy.getId(), date);
        if (absentees.isEmpty()) {
            log.info("미등원 감지: 지점={} 날짜={} 대상 없음", academy.getName(), date);
            return 0;
        }

        String formattedDate = DATE_FORMAT.format(date.atStartOfDay(TimeConfig.KST));
        for (StudentEnrollment enrollment : absentees) {
            Student student = enrollment.getStudent();
            // ★★ 앱을 설치하지 않은 보호자에게도 보낸다. 미등원은 끌 수 없는 필수 알림이라
            //   수신처가 없으면 **무단결석을 아무도 모르게 된다.** 신입생 동의서가 보호자
            //   번호를 필수로 수집하므로 번호만 있어도 보내는 것이 맞다.
            //   ★ 수신자 해석은 GuardianRecipientResolver 한 곳이다 — 출결 알림·수동 발송과
            //     조건이 갈리면 「미리보기엔 빠졌는데 실제로는 갔다」가 된다.
            for (var recipient : recipientResolver.of(student.getId())) {
                if (!recipient.hasPhone()) {
                    // 번호가 없으면 알림톡이 나갈 곳이 없다. 조용히 넘기지 않고 남긴다 —
                    // 필수 알림이라 「왜 안 왔나」에 답해야 한다
                    log.warn("미등원 알림 수신처 없음: studentId={}, guardianId={}",
                            student.getId(), recipient.guardianId());
                    continue;
                }
                // 배치가 재실행돼도 같은 보호자에게 같은 날 두 번 나가지 않도록 막는다.
                // ★ 계정이 없어도 보호자 행은 있으므로 보호자 id 를 키로 쓴다 —
                //   계정 id 로 두면 번호만 있는 보호자의 멱등성이 성립하지 않는다
                String dedupKey = dedupKey(student.getId(), date, recipient.guardianId());

                notificationService.send(recipient.account() != null
                        ? NotificationCommand.forStudent(
                                NotificationEvent.MISSING_ATTENDANCE, recipient.account(),
                                student, academy, enrollment.getYear(),
                                Map.of("attendanceDate", formattedDate), dedupKey)
                        : NotificationCommand.forStudentPhone(
                                NotificationEvent.MISSING_ATTENDANCE, recipient.phone(),
                                recipient.name(), student, academy, enrollment.getYear(),
                                Map.of("attendanceDate", formattedDate), dedupKey));
            }
        }

        log.info("미등원 감지: 지점={} 날짜={} 대상 {}명", academy.getName(), date, absentees.size());
        return absentees.size();
    }

    private String dedupKey(Long studentId, LocalDate date, Long recipientId) {
        return "MISSING_ATTENDANCE:%d:%s:%d".formatted(studentId, date, recipientId);
    }
}
