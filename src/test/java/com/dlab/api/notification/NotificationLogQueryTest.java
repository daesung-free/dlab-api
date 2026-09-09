package com.dlab.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationStatus;
import com.dlab.domain.notification.repository.NotificationLogRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발송 이력 조회 (F-4.4 · F-C-6).
 *
 * <p><b>집계 쿼리를 테스트로 못박는 이유</b> — {@code CAST(... AS date)}와 조건부 SUM 이
 * 섞여 있어 <b>컴파일로는 안 걸리고 부를 때 터진다</b>. 화면이 한 탭을 통째로 못 여는데
 * 원인이 쿼리 안쪽이라 찾기도 오래 걸린다.
 */
@SpringBootTest
@Transactional
class NotificationLogQueryTest {

    @Autowired NotificationLogRepository logRepository;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    Student student;
    Account recipient;

    @BeforeEach
    void setUp() {
        academy = new Academy("NL1", "발송이력테스트", LocalTime.of(9, 0));
        em.persist(academy);
        student = new Student("NLU1", "김민지", "010-1111-2222");
        em.persist(student);

        // 수신자는 필수다 — 알림은 "누구에게 갔나"가 없으면 이력의 의미가 없다
        ParentGuardian guardian = new ParentGuardian("김보호", "010-9999-8888", "M");
        em.persist(guardian);
        recipient = Account.forGuardian(guardian, "nl-parent", "x");
        em.persist(recipient);
        em.flush();
    }

    @Test
    @DisplayName("★ 실패·건너뜀도 목록에 남는다 — 안 나간 것을 못 보면 \"왜 안 왔지\"에 답할 수 없다")
    void skippedAndFailedRemain() {
        save(NotificationStatus.SENT);
        save(NotificationStatus.SKIPPED);
        save(NotificationStatus.FAILED);
        em.flush();

        var page = logRepository.search(academy.getId(), null, null, null, null,
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(3);
    }

    @Test
    @DisplayName("★★ 집계가 상태별로 갈린다 — 알림톡은 건당 과금이라 sent 가 정산 근거다")
    void summarySplitsByStatus() {
        save(NotificationStatus.SENT);
        save(NotificationStatus.SENT);
        save(NotificationStatus.SKIPPED);
        em.flush();

        var rows = logRepository.summarize(academy.getId(),
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(((Number) row[3]).longValue()).isEqualTo(3);  // total
        assertThat(((Number) row[4]).longValue()).isEqualTo(2);  // sent
        assertThat(((Number) row[6]).longValue()).isEqualTo(1);  // skipped
    }

    @Test
    @DisplayName("학생으로 좁힌다 — \"이 학생에게 언제 무엇이 갔나\"")
    void filterByStudent() {
        save(NotificationStatus.SENT);
        em.flush();

        assertThat(logRepository.search(academy.getId(), null, null, null, student.getId(),
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS),
                PageRequest.of(0, 20)).getContent()).hasSize(1);
    }

    private void save(NotificationStatus status) {
        NotificationLog log = new NotificationLog(academy, (short) 2026,
                NotificationEvent.MISSING_ATTENDANCE, NotificationChannel.KAKAO_ALIMTALK,
                recipient, student, "제목", "본문", java.util.Map.of(),
                "test-" + status + "-" + System.nanoTime());
        switch (status) {
            case SENT -> log.markSent();
            case FAILED -> log.markFailed("전송 실패");
            case SKIPPED -> log.markSkipped("문구 미확정");
            default -> { }
        }
        em.persist(log);
    }
}
