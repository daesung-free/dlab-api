package com.dlab.api.academy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.kiosk.entity.BranchConfigAction;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import com.dlab.domain.kiosk.service.BranchConfigService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점 설정 관리 (F-4.10-7).
 *
 * <p>SUPER_ADMIN 제한은 컨트롤러 {@code @PreAuthorize}가 건다 — 여기서는 값 처리를 본다.
 */
@SpringBootTest
@Transactional
class BranchConfigTest {

    @Autowired BranchConfigService branchConfigService;
    @Autowired BranchConfigRepository configRepository;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
    }

    @Test
    @DisplayName("★★ 시크릿 원문은 재발급 응답에만 있고 조회는 마스킹이다")
    void secretIsOnlyReturnedOnIssue() {
        var issued = branchConfigService.issueKioskCredential(bundang.getId());
        em.flush();

        assertThat(issued.secret()).isNotBlank();

        var view = branchConfigService.get(bundang.getId());
        assertThat(view.kioskSecretMasked()).endsWith("****");
        assertThat(view.kioskSecretMasked()).doesNotContain(issued.secret());
    }

    @Test
    @DisplayName("★ 마스킹이 실제 길이를 노출하지 않는다 — 자리수만 알아도 단서가 된다")
    void maskDoesNotLeakLength() {
        var issued = branchConfigService.issueKioskCredential(bundang.getId());
        em.flush();

        String masked = branchConfigService.get(bundang.getId()).kioskSecretMasked();

        assertThat(masked).hasSize(8);                       // 앞 4자 + ****
        assertThat(issued.secret().length()).isGreaterThan(8);
    }

    @Test
    @DisplayName("★ 재발급하면 값이 바뀐다 — 같은 값이 나오면 재발급이 아니다")
    void reissueChangesSecret() {
        String first = branchConfigService.issueKioskCredential(bundang.getId()).secret();
        em.flush();
        String second = branchConfigService.issueKioskCredential(bundang.getId()).secret();
        em.flush();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("★★ 재발급이 감사로그에 남는다 — 키오스크가 멈췄을 때 누가 돌렸는지 못 찾으면 추적이 막힌다")
    void issueIsAudited() {
        branchConfigService.issueKioskCredential(bundang.getId());
        em.flush();

        var history = branchConfigService.history(bundang.getId());

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction())
                .isEqualTo(BranchConfigAction.KIOSK_CREDENTIAL_ISSUED);
    }

    @Test
    @DisplayName("★ 감사로그에 값 자체는 남기지 않는다 — 폐기한 시크릿이 영구 보존된다")
    void auditDoesNotStoreValues() {
        var issued = branchConfigService.issueKioskCredential(bundang.getId());
        branchConfigService.changePgMerchantCode(bundang.getId(), "MID-9999");
        em.flush();

        assertThat(branchConfigService.history(bundang.getId()))
                .allSatisfy(h -> assertThat(h.getDetail())
                        .doesNotContain(issued.secret())
                        .doesNotContain("MID-9999"));
    }

    @Test
    @DisplayName("이력은 최신순이다")
    void historyIsNewestFirst() {
        branchConfigService.issueKioskCredential(bundang.getId());
        branchConfigService.changeNebulaDeviceId(bundang.getId(), "AP-01");
        em.flush();

        var history = branchConfigService.history(bundang.getId());

        assertThat(history.get(0).getAction())
                .isEqualTo(BranchConfigAction.NEBULA_DEVICE_CHANGED);
    }

    @Test
    @DisplayName("★ 설정이 없는 지점도 목록에 나온다 — 화면이 지점 전체를 그린다")
    void academyWithoutConfigStillListed() {
        var list = branchConfigService.list();

        assertThat(list).anySatisfy(v -> {
            assertThat(v.academyId()).isEqualTo(bundang.getId());
            assertThat(v.kioskClientId()).isNull();
            assertThat(v.policy()).isEmpty();
        });
    }

    @Test
    @DisplayName("첫 값을 넣을 때 설정 행이 생긴다")
    void configRowIsCreatedOnFirstWrite() {
        assertThat(configRepository.findByAcademyIdAndDeletedFalse(bundang.getId())).isEmpty();

        branchConfigService.changePgMerchantCode(bundang.getId(), "MID-1234");
        em.flush();

        assertThat(configRepository.findByAcademyIdAndDeletedFalse(bundang.getId())).isPresent();
    }

    @Test
    @DisplayName("★ 정책은 통째로 갈아끼운다 — 병합하면 항목을 지울 방법이 없다")
    void policyIsReplacedNotMerged() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("lateGraceMinutes", "10");
        first.put("mealDeadlineDays", "3");
        branchConfigService.replacePolicy(bundang.getId(), first);
        em.flush();

        branchConfigService.replacePolicy(bundang.getId(), Map.of("mealDeadlineDays", "5"));
        em.flush();
        em.clear();

        var policy = branchConfigService.get(bundang.getId()).policy();

        assertThat(policy).containsEntry("mealDeadlineDays", "5");
        assertThat(policy).doesNotContainKey("lateGraceMinutes");
    }

    @Test
    @DisplayName("빈 문자열은 null로 저장한다 — 빈 값과 미설정이 갈리면 화면이 둘을 다르게 그린다")
    void blankBecomesNull() {
        branchConfigService.changePgMerchantCode(bundang.getId(), "MID-1234");
        branchConfigService.changePgMerchantCode(bundang.getId(), "   ");
        em.flush();
        em.clear();

        assertThat(branchConfigService.get(bundang.getId()).pgMerchantCodeMasked()).isNull();
    }
}
