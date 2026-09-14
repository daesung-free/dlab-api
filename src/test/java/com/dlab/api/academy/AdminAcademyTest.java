package com.dlab.api.academy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.service.AcademyService;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점 관리.
 *
 * <p>등록 기능은 없다 — 9개 고정이라 마이그레이션이 심는다.
 */
@SpringBootTest
@Transactional
class AdminAcademyTest {

    @Autowired AcademyService academyService;
    @Autowired AcademyRepository academyRepository;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    AuthPrincipal headOffice;
    AuthPrincipal bundangAdmin;

    @BeforeEach
    void setUp() {
        // 시드(db/seed)는 테스트에서 제외되므로 여기서 직접 만든다 —
        // 테스트마다 지점을 만드는 기존 테스트들과 acad_cd가 겹치면 안 되기 때문이다
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);
        em.flush();

        headOffice = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        bundangAdmin = AuthPrincipal.of(2L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    @Test
    @DisplayName("★★ 시드 SQL이 키오스크 stores와 1:1로 맞는다 — 어긋나면 그 지점만 조용히 연동이 끊긴다")
    void seedMatchesKioskStores() throws Exception {
        // 시드는 운영·로컬에서만 도는 별도 위치(db/seed)라 테스트 DB엔 없다.
        // 대신 SQL 자체를 읽어 키오스크 R__seed_dsa_credentials.sql과 대조한다
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/seed/V20260807_1600__academy_seed.sql"));

        // acad_cd는 연속이 아니다 — 31~34 다음이 42다
        assertThat(sql).contains("('31', '분당', 'Dlab 분당', 'DS-001')")
                .contains("('34', '김포', 'Dlab 김포', 'DS-004')")
                .contains("('42', '부천', 'Dlab 부천', 'DS-005')")
                .contains("('46', '송파', 'Dlab 송파', 'DS-009')");
        // ★ 대전·대구는 2026-08-26에 추가됐다. store_code 는 키오스크 어드민에서
        //   확인한 값이고, 추측이 아니다 — 어긋난 값이 들어가면 "설정은 됐는데
        //   연동이 안 되는" 상태가 되어 원인을 찾기가 더 어려워진다
        assertThat(sql).contains("('47', '대전', 'Dlab 대전', 'DS-010')")
                .contains("('48', '대구', 'Dlab 대구', 'DS-011')");
        // 11개 전부 store_code 를 갖는다 — 하나라도 비면 그 지점만 조용히 연동이 끊긴다
        assertThat(sql.lines().filter(l -> l.contains("'DS-")).count()).isEqualTo(11);
    }

    @Test
    @DisplayName("★ 지점 관리자에게는 자기 지점만 보인다")
    void branchAdminSeesOwnAcademyOnly() {
        assertThat(academyService.findAll(bundangAdmin, false))
                .extracting(Academy::getAcadCd).containsExactly("31");
    }

    @Test
    @DisplayName("비활성 지점은 기본 목록에서 빠진다 — 셀렉트에 죽은 지점이 뜨면 안 된다")
    void inactiveIsHiddenByDefault() {
        academyService.changeActive(headOffice, ilsan.getId(), false);
        em.flush();
        em.clear();

        assertThat(academyService.findAll(headOffice, false))
                .extracting(Academy::getAcadCd).doesNotContain("32");
        assertThat(academyService.findAll(headOffice, true))
                .extracting(Academy::getAcadCd).contains("32");
    }

    @Test
    @DisplayName("★ 등원 기준 시각을 고칠 수 있다 — 지각 판정 기준이다")
    void canUpdateAttendanceDeadline() {
        academyService.update(bundangAdmin, bundang.getId(), "분당", "Dlab 분당",
                LocalTime.of(8, 30));
        em.flush();
        em.clear();

        assertThat(academyRepository.findById(bundang.getId()).orElseThrow()
                .getAttendanceDeadline()).isEqualTo(LocalTime.of(8, 30));
    }

    @Test
    @DisplayName("★★ 지점코드·연동코드는 수정 대상이 아니다 — 키오스크가 이 값으로 인증한다")
    void codesAreNotUpdatable() {
        academyService.update(bundangAdmin, bundang.getId(), "이름바꿈", "풀네임바꿈",
                LocalTime.of(9, 0));
        em.flush();
        em.clear();

        Academy after = academyRepository.findById(bundang.getId()).orElseThrow();
        assertThat(after.getAcadNm()).isEqualTo("이름바꿈");
        // acad_cd·store_code는 updateInfo가 아예 안 건드린다. 바꿀 수단 자체가 없다
        assertThat(after.getAcadCd()).isEqualTo("31");
    }

    @Test
    @DisplayName("★ 다른 지점은 못 고친다")
    void cannotUpdateOtherAcademy() {
        assertThatThrownBy(() -> academyService.update(bundangAdmin, ilsan.getId(),
                "일산", null, LocalTime.of(9, 0)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 활성 여부는 본사만 — 끄면 그 지점 키오스크가 전면 중단된다")
    void onlyHeadOfficeCanDeactivate() {
        assertThatThrownBy(() -> academyService.changeActive(bundangAdmin, bundang.getId(), false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본사");

        assertThatCode(() -> academyService.changeActive(headOffice, bundang.getId(), false))
                .doesNotThrowAnyException();
    }
}
