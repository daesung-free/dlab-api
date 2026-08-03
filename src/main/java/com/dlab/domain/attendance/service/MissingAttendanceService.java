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
    private final AccountRepository accountRepository;
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
            for (Account guardian : accountRepository.findGuardianAccountsOfStudent(student.getId())) {
                notificationService.send(NotificationCommand.forStudent(
                        NotificationEvent.MISSING_ATTENDANCE,
                        guardian,
                        student,
                        academy,
                        enrollment.getYear(),
                        Map.of("attendanceDate", formattedDate),
                        // 배치가 재실행돼도 같은 학부모에게 같은 날 두 번 나가지 않도록 막는다
                        dedupKey(student.getId(), date, guardian.getId())));
            }
        }

        log.info("미등원 감지: 지점={} 날짜={} 대상 {}명", academy.getName(), date, absentees.size());
        return absentees.size();
    }

    private String dedupKey(Long studentId, LocalDate date, Long recipientId) {
        return "MISSING_ATTENDANCE:%d:%s:%d".formatted(studentId, date, recipientId);
    }
}
