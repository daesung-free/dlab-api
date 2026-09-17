package com.dlab.api.readiness;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.PgSite;
import com.dlab.domain.readiness.ReadinessService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 준비 상태 점검.
 *
 * <p>지키려는 것 — <b>빠진 설정을 실제로 잡아낼 것</b>, <b>「아예 안 되는 것」과 「조용히
 * 틀리는 것」을 구분할 것</b>(합치면 전부 무시하게 된다).
 */
@SpringBootTest
@Transactional
class ReadinessTest {

    @Autowired ReadinessService service;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2097;

    Academy academy;

    @BeforeEach
    void setUp() {
        academy = new Academy("99", "점검지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();
    }

    private ReadinessService.Check find(String code) {
        return service.check(YEAR).academies().stream()
                .filter(a -> a.academyId().equals(academy.getId()))
                .flatMap(a -> a.checks().stream())
                .filter(c -> c.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("★ 승인 정책이 없으면 잡는다 — 없으면 그 유형 신청이 전부 거절된다")
    void detectsMissingApprovalItem() {
        ReadinessService.Check check = find("APPROVAL_ITEM");

        assertThat(check.blocking()).isTrue();
        assertThat(check.detail()).contains("거절");
    }

    @Test
    @DisplayName("승인 정책을 다 넣으면 통과한다")
    void passesWhenApprovalItemsExist() {
        for (RequestType type : RequestType.values()) {
            em.persist(new ApprovalItem(academy, YEAR, type, ApproverType.PARENT,
                    (short) 10, ApproverType.TEACHER));
        }
        em.flush();

        assertThat(find("APPROVAL_ITEM").ok()).isTrue();
    }

    @Test
    @DisplayName("★ 사이트코드가 있어도 상점관리자 계정이 비면 잡는다 — 결제 시점에야 거절된다")
    void detectsMissingMgmtId() {
        em.persist(new PgSite(academy, PgPurpose.TUITION, PgChannel.BUYLINK,
                "AO8M2", "학원비 바이링크", null));
        em.flush();

        ReadinessService.Check check = find("PG_SITE_TUITION");
        assertThat(check.blocking()).isTrue();
        assertThat(check.detail()).contains("상점관리자");
    }

    @Test
    @DisplayName("★ 등원 기준시각이 기본값 그대로면 경고한다 — 막지는 않는다")
    void warnsOnDefaultDeadline() {
        ReadinessService.Check check = find("ATTENDANCE_DEADLINE");

        assertThat(check.warning()).isTrue();
        // 돌기는 도는 항목이라 blocker 로 세면 안 된다 — 합치면 전부 무시하게 된다
        assertThat(check.blocking()).isFalse();
    }

    @Test
    @DisplayName("★ 점검 연도를 받는다 — 연말에 내년을 미리 확인할 수 있어야 한다")
    void checksRequestedYear() {
        for (RequestType type : RequestType.values()) {
            em.persist(new ApprovalItem(academy, YEAR, type, ApproverType.PARENT,
                    (short) 10, ApproverType.TEACHER));
        }
        em.flush();

        // 올해는 넣었고 내년은 안 넣었다 — 내년을 물으면 걸려야 한다
        assertThat(find("APPROVAL_ITEM").ok()).isTrue();
        assertThat(service.check((short) (YEAR + 1)).academies().stream()
                .filter(a -> a.academyId().equals(academy.getId()))
                .flatMap(a -> a.checks().stream())
                .filter(c -> c.code().equals("APPROVAL_ITEM"))
                .findFirst().orElseThrow().blocking()).isTrue();
    }

    @Test
    @DisplayName("문자 발송이 목업이면 경고한다 — API 는 200 인데 아무에게도 안 간다")
    void warnsOnMockSmsSender() {
        ReadinessService.Check check = service.check(YEAR).common().stream()
                .filter(c -> c.code().equals("SMS_SENDER"))
                .findFirst().orElseThrow();

        assertThat(check.warning()).isTrue();
        assertThat(check.detail()).contains("목업");
    }
}
