package com.dlab.domain.payment;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.PgSite;
import com.dlab.domain.payment.service.PgSiteService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.integration.pg.KcpBuyLinkClient;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 사이트코드 등록.
 *
 * <p>지키려는 것 — <b>한 칸에 코드는 하나</b>, <b>상점관리자 계정이 비면 결제가 안 된다는
 * 것을 미리 알 것</b>, <b>내려도 지워지지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class PgSiteTest {

    @Autowired PgSiteService service;
    @Autowired EntityManager em;

    @MockitoBean KcpBuyLinkClient client;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
    }

    @Test
    @DisplayName("★ 상점관리자 계정까지 넣어야 바이링크가 돈다 — reg_id 로 나간다")
    void storesMgmtId() {
        PgSite site = service.create(null, PgPurpose.TUITION, PgChannel.BUYLINK,
                "AO8M2", "dshw_41", "대성학력개발 바이링크", null);

        assertThat(site.getMgmtId()).isEqualTo("dshw_41");
        assertThat(site.isShared()).isTrue();
    }

    @Test
    @DisplayName("★ 같은 지점·용도·채널에 코드를 두 개 두지 않는다 — 어느 쪽으로 보낼지 정해지지 않는다")
    void rejectsDuplicateScope() {
        service.create(bundang.getId(), PgPurpose.MEAL, PgChannel.BUYLINK,
                "AO8M4", "dlab10", "디온푸드 바이링크", null);

        assertThatThrownBy(() -> service.create(bundang.getId(), PgPurpose.MEAL, PgChannel.BUYLINK,
                "AO8M9", "dlab99", "디온푸드 바이링크(신규)", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AO8M4");
    }

    @Test
    @DisplayName("용도나 채널이 다르면 같은 지점이어도 따로 등록된다 — MID 가 그렇게 갈린다")
    void allowsOtherPurposeAndChannel() {
        service.create(bundang.getId(), PgPurpose.TUITION, PgChannel.BUYLINK,
                "AO8M2", "dshw_41", "학원비 바이링크", null);
        service.create(bundang.getId(), PgPurpose.TUITION, PgChannel.TERMINAL,
                "AO8M3", null, "학원비 단말기", null);
        service.create(bundang.getId(), PgPurpose.MEAL, PgChannel.BUYLINK,
                "AO8M4", "dlab10", "급식비 바이링크", null);

        assertThat(service.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("수정에서 비워 보낸 항목은 바뀌지 않는다 — 화면이 일부만 고친다")
    void keepsBlankFields() {
        PgSite site = service.create(null, PgPurpose.TUITION, PgChannel.BUYLINK,
                "AO8M2", "dshw_41", "대성학력개발 바이링크", null);

        service.update(site.getId(), null, "dshw_99", null, null);

        assertThat(site.getSiteCd()).isEqualTo("AO8M2");
        assertThat(site.getDisplayName()).isEqualTo("대성학력개발 바이링크");
        assertThat(site.getMgmtId()).isEqualTo("dshw_99");
    }

    @Test
    @DisplayName("★ 내려도 목록에 남는다 — 과거 결제가 어느 가맹점으로 나갔는지가 정산 근거다")
    void deactivateKeepsRow() {
        PgSite site = service.create(null, PgPurpose.TUITION, PgChannel.BUYLINK,
                "AO8M2", "dshw_41", "대성학력개발 바이링크", null);

        service.changeActive(site.getId(), false);

        assertThat(site.isActive()).isFalse();
        assertThat(service.findAll()).extracting(PgSite::getId).contains(site.getId());
    }
}
