package com.dlab.api.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.facility.entity.Building;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동탄 별관 {@code building} 행을 만드는 마이그레이션의 <b>가드</b>.
 *
 * <h2>★ 왜 테스트가 필요한가 — 실제로 사고가 날 뻔했다</h2>
 * 처음 가드는 {@code code = 'ANNEX'} 인 행이 없으면 넣는 것이었는데, <b>운영의 동탄
 * 별관은 코드가 {@code '2'}</b> 다(2026-10-23 실측). 그대로 배포하면 <b>별관이 두 개</b>가
 * 되고, 빈 관이 화면에 떠 좌석을 어느 관에 넣을지 갈린다.
 *
 * <p>마이그레이션 테스트가 어려운 이유는 <b>테스트 DB 에 지점이 하나도 없어</b> 그 INSERT
 * 가 0건으로 조용히 지나가기 때문이다 — 처음 PR 이 이 사고를 못 잡은 것이 정확히 그래서다.
 * 그래서 여기서는 <b>운영과 같은 모양을 만들어 놓고</b> 같은 문장을 돌린다.
 *
 * <p>문장은 <b>마이그레이션 파일에서 그대로 읽어온다.</b> 테스트에 복사해 두면 파일을
 * 고쳤을 때 테스트만 옛 문장을 지키게 된다.
 */
@SpringBootTest
@Transactional
class DongtanAnnexMigrationTest {

    private static final String MIGRATION =
            "db/migration/V20261023140000__seat_style_and_dongtan_annex.sql";

    @Autowired EntityManager em;

    Academy dongtan;

    @BeforeEach
    void setUp() {
        dongtan = new Academy("33", "동탄", LocalTime.of(9, 0));
        em.persist(dongtan);
        em.persist(new Building(dongtan, Building.MAIN_CODE, "본관", (short) 0, 0));
        em.flush();
    }

    @Test
    @DisplayName("★★ 운영처럼 code='2' 별관이 이미 있으면 관이 늘지 않는다")
    void doesNotDuplicateExistingAnnexWithDifferentCode() {
        // 운영 실측: 동탄은 code='2' name='별관' offset=1000 이다. 'ANNEX' 가 아니다
        em.persist(new Building(dongtan, "2", "별관", (short) 1, 1000));
        em.flush();

        int inserted = runAnnexInsert();

        assertThat(inserted).isZero();
        assertThat(annexCodesOfDongtan()).containsExactly("2");
    }

    @Test
    @DisplayName("별관이 없으면 offset 1000 으로 만든다 — 새로 올리는 환경용")
    void createsAnnexWhenMissing() {
        int inserted = runAnnexInsert();

        assertThat(inserted).isEqualTo(1);
        assertThat(annexCodesOfDongtan()).containsExactly("ANNEX");

        // ★ offset 은 만든 뒤 바꿀 수 없다 — 만드는 시점에 1000 이어야 한다
        assertThat(offsetOf("ANNEX")).isEqualTo(1000);
    }

    @Test
    @DisplayName("★ 두 번 돌려도 늘지 않는다")
    void idempotent() {
        assertThat(runAnnexInsert()).isEqualTo(1);
        assertThat(runAnnexInsert()).isZero();
        assertThat(annexCodesOfDongtan()).hasSize(1);
    }

    @Test
    @DisplayName("★ 삭제된 별관 행은 활성으로 보지 않는다 — 분당·일산에 그런 행이 남아 있다")
    void ignoresDeletedAnnexRows() {
        Building stale = new Building(dongtan, "2", "지운 관", (short) 1, 1000);
        em.persist(stale);
        stale.markDeleted();
        em.flush();

        // 지운 행만 있으면 별관이 없는 것이다. 오인하면 그 지점은 영영 별관을 못 만든다
        assertThat(runAnnexInsert()).isEqualTo(1);
        assertThat(annexCodesOfDongtan()).containsExactly("ANNEX");
    }

    @Test
    @DisplayName("★ 동탄이 아닌 지점은 건드리지 않는다 — 빈 관이 화면에 뜬다")
    void onlyDongtan() {
        Academy daegu = new Academy("48", "대구", LocalTime.of(9, 0));
        em.persist(daegu);
        em.persist(new Building(daegu, Building.MAIN_CODE, "본관", (short) 0, 0));
        em.flush();

        runAnnexInsert();

        Long daeguBuildings = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM building WHERE academy_id = :id AND NOT is_deleted")
                .setParameter("id", daegu.getId()).getSingleResult()).longValue();
        assertThat(daeguBuildings).isEqualTo(1);
    }

    // ── 준비물 ───────────────────────────────────────────────────────────────

    /** 마이그레이션 파일의 {@code INSERT INTO building} 문을 그대로 실행한다. */
    private int runAnnexInsert() {
        int affected = em.createNativeQuery(annexInsertStatement()).executeUpdate();
        em.clear();
        return affected;
    }

    private String annexInsertStatement() {
        String sql;
        try {
            sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("마이그레이션 파일을 읽지 못했다: " + MIGRATION, e);
        }
        // 주석을 먼저 걷어낸다 — 주석 안의 예시 SQL 이 문장으로 잡히면 안 된다
        String stripped = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

        List<String> inserts = Arrays.stream(stripped.split(";"))
                .map(String::strip)
                .filter(s -> s.toUpperCase().startsWith("INSERT INTO BUILDING"))
                .toList();
        assertThat(inserts)
                .as("마이그레이션에 building INSERT 가 하나여야 한다")
                .hasSize(1);
        return inserts.get(0);
    }

    /** 동탄의 활성 별관(= offset 이 0 이 아닌 관) 코드. */
    @SuppressWarnings("unchecked")
    private List<String> annexCodesOfDongtan() {
        return em.createNativeQuery("""
                        SELECT code FROM building
                         WHERE academy_id = :id AND NOT is_deleted AND seat_cd_offset > 0
                         ORDER BY code
                        """)
                .setParameter("id", dongtan.getId()).getResultList();
    }

    private int offsetOf(String code) {
        return ((Number) em.createNativeQuery("""
                        SELECT seat_cd_offset FROM building
                         WHERE academy_id = :id AND code = :code AND NOT is_deleted
                        """)
                .setParameter("id", dongtan.getId())
                .setParameter("code", code).getSingleResult()).intValue();
    }
}
