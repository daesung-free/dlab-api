package com.dlab.api.master;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.master.entity.Scholarship;
import com.dlab.domain.master.entity.ScholarshipMaster;
import com.dlab.domain.master.service.MasterDataService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
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
 * 장학 <b>표시 이름</b>을 서버가 내린다.
 *
 * <h2>★★ 왜 화면이 변환하면 안 되는가</h2>
 * 저장된 값은 코드({@code CSAT_100})다. 청구의 {@code discount_source} 는
 * <b>화면이 보낸 문자열이 그대로 박히므로</b>, 변환을 화면마다 만들면
 * <b>매출장·청구 근거·취소 판정이 서로 다른 이름을 보게 된다.</b>
 *
 * <h2>★★ {@code registered=false} 는 표시 문제가 아니다</h2>
 * 마스터에 없는 코드라는 뜻이고, <b>취소 판정이 그 학생을 조용히 건너뛴다</b> —
 * {@code scholarship_cancel_rule.scholarship_type} 과 문자열 일치로 걸리기 때문이다.
 * 오류도 안 나고 검토 목록에 안 뜰 뿐이라, <b>이 값을 화면이 띄워야 드러난다.</b>
 */
@SpringBootTest
@Transactional
class ScholarshipDisplayNameTest {

    @Autowired MasterDataService masterDataService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    short year;
    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        year = (short) java.time.LocalDate.now(clock).getYear();
        academy = new Academy("SD1", "장학이름테스트", LocalTime.of(9, 0));
        em.persist(academy);
        Student s = new Student("SD-0001", "장학학생", "010-0000-0000");
        em.persist(s);
        enrollment = new StudentEnrollment(s, academy, year, "2094-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        // 공통 마스터 한 줄 — 실제 시드와 같은 코드·이름을 쓴다
        em.persist(new ScholarshipMaster(null, year, "CSAT_100", "수능 100%",
                new BigDecimal("100.00"), (short) 1, null));
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    @Test
    @DisplayName("★ 부여 응답에 표시 이름이 함께 온다 — 화면이 코드를 변환하지 않아도 된다")
    void grantReturnsDisplayName() {
        var granted = masterDataService.grantScholarship(enrollment.getId(), "CSAT_100",
                new BigDecimal("100.00"), admin);

        assertThat(granted.scholarship().getScholarshipType()).isEqualTo("CSAT_100");
        assertThat(granted.displayName()).isEqualTo("수능 100%");
        assertThat(granted.registered()).isTrue();
    }

    @Test
    @DisplayName("★ 조회도 이름을 붙여 내린다 — 청구의 discountSource 로 이 값을 보내면 된다")
    void listResolvesDisplayName() {
        masterDataService.grantScholarship(enrollment.getId(), "CSAT_100",
                new BigDecimal("100.00"), admin);
        em.flush();
        em.clear();

        var items = masterDataService.scholarshipsOf(enrollment.getId());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).displayName()).isEqualTo("수능 100%");
        assertThat(items.get(0).registered()).isTrue();
    }

    @Test
    @DisplayName("★★ 마스터에 없는 코드는 registered=false 다 — 그 학생이 취소 판정에서 빠진다는 신호다")
    void unknownCodeIsFlagged() {
        // 마스터를 거치지 않고 직접 넣는다 — 과거 데이터나 마스터 정비 전 부여분이 이 모양이다
        em.persist(new Scholarship(academy, enrollment, "LEGACY_70", new BigDecimal("70.00")));
        em.flush();
        em.clear();

        var items = masterDataService.scholarshipsOf(enrollment.getId());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).registered()).isFalse();
        // 이름을 못 찾으면 코드를 그대로 쓴다 — 빈 칸이면 화면이 「장학 없음」으로 읽는다
        assertThat(items.get(0).displayName()).isEqualTo("LEGACY_70");
    }
}
