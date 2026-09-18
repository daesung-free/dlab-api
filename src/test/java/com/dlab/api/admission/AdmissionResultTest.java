package com.dlab.api.admission;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.admission.entity.*;
import com.dlab.domain.admission.service.AdmissionResultService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
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
 * 실적 관리 (F-4.10-6).
 *
 * <p>지키려는 것 — <b>수시 6·정시 3 을 서버가 지킬 것</b>, <b>불합격과 등록포기를 섞지
 * 않을 것</b>(실적에서 완전히 다른 숫자다), <b>발표 전을 실패로 세지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class AdmissionResultTest {

    @Autowired AdmissionResultService service;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2094;

    Academy bundang;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Student student = new Student("DL-A1", "김실적", "010-1111-2222");
        em.persist(student);
        enrollment = new StudentEnrollment(student, bundang, YEAR, "2094-0001", null,
                GradeType.HIGH3);
        em.persist(enrollment);
        em.flush();

        admin = new AuthPrincipal(1L, "admin", bundang.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);
    }

    private AdmissionResult add(AdmissionType type, String university,
                                AdmissionResultStatus result) {
        return service.create(admin, enrollment.getId(), type, university, "컴퓨터공학과",
                "학생부종합", result, AdmissionSource.STAFF, null);
    }

    @Test
    @DisplayName("★ 수시는 6개까지다 — 화면에서만 막으면 두 창에서 동시에 넣을 때 통과한다")
    void enforcesEarlyLimit() {
        for (int i = 1; i <= 6; i++) {
            add(AdmissionType.EARLY, "대학" + i, AdmissionResultStatus.PENDING);
        }
        em.flush();

        assertThatThrownBy(() -> add(AdmissionType.EARLY, "일곱번째", AdmissionResultStatus.PENDING))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("수시는 6개");
    }

    @Test
    @DisplayName("★ 정시는 3개까지다 — 수시와 정원이 다르므로 따로 센다")
    void enforcesRegularLimitSeparately() {
        for (int i = 1; i <= 6; i++) {
            add(AdmissionType.EARLY, "수시대학" + i, AdmissionResultStatus.PENDING);
        }
        // 수시가 꽉 차도 정시는 따로다
        for (int i = 1; i <= 3; i++) {
            add(AdmissionType.REGULAR, "정시대학" + i, AdmissionResultStatus.PENDING);
        }
        em.flush();

        assertThatThrownBy(() -> add(AdmissionType.REGULAR, "네번째", AdmissionResultStatus.PENDING))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("정시는 3개");
    }

    @Test
    @DisplayName("★★ 불합격과 등록포기는 다른 숫자다 — boolean 하나면 구분되지 않는다")
    void separatesFailedFromGaveUp() {
        add(AdmissionType.EARLY, "합격대학", AdmissionResultStatus.PASSED);
        add(AdmissionType.EARLY, "포기대학", AdmissionResultStatus.GAVE_UP);
        add(AdmissionType.EARLY, "불합격대학", AdmissionResultStatus.FAILED);
        em.flush();

        var stats = service.statistics(admin, bundang.getId(), YEAR,
                LocalDate.now(), LocalDate.now());

        assertThat(stats.byResult().get(AdmissionResultStatus.FAILED)).isEqualTo(1);
        assertThat(stats.byResult().get(AdmissionResultStatus.GAVE_UP)).isEqualTo(1);
        // 등록포기도 "붙은 것" 이라 합격에 든다 — 등록 여부는 byResult 가 따로 답한다
        assertThat(stats.passed()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 발표 전은 실패로 세지 않는다 — 분모에 넣으면 합격률이 낮게 나온다")
    void pendingIsNotCountedAsDecided() {
        add(AdmissionType.EARLY, "발표난대학", AdmissionResultStatus.PASSED);
        add(AdmissionType.EARLY, "발표전대학", AdmissionResultStatus.PENDING);
        em.flush();

        var stats = service.statistics(admin, bundang.getId(), YEAR,
                LocalDate.now(), LocalDate.now());

        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.decided()).isEqualTo(1);
        assertThat(stats.passed()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 고치면 입력 주체가 직원이 된다 — 학생이 적어낸 값과 구분해야 한다")
    void updateMarksStaffSource() {
        AdmissionResult row = service.create(admin, enrollment.getId(), AdmissionType.EARLY,
                "학생이적은대학", "학과", null, null, AdmissionSource.STUDENT, null);
        em.flush();
        assertThat(row.getSource()).isEqualTo(AdmissionSource.STUDENT);

        service.update(admin, row.getId(), null, null, null, null,
                AdmissionResultStatus.PASSED, null);

        assertThat(row.getSource()).isEqualTo(AdmissionSource.STAFF);
    }

    @Test
    @DisplayName("★ 수시를 정시로 옮길 때도 정원을 본다 — 옮겨 가는 쪽이 넘칠 수 있다")
    void movingTypeChecksLimit() {
        for (int i = 1; i <= 3; i++) {
            add(AdmissionType.REGULAR, "정시" + i, AdmissionResultStatus.PENDING);
        }
        AdmissionResult early = add(AdmissionType.EARLY, "수시1", AdmissionResultStatus.PENDING);
        em.flush();

        assertThatThrownBy(() -> service.update(admin, early.getId(), AdmissionType.REGULAR,
                null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("정시는 3개");
    }

    @Test
    @DisplayName("자동완성은 쌓인 값에서 나온다 — 대학 마스터를 기다리지 않는다")
    void suggestsFromEnteredValues() {
        add(AdmissionType.EARLY, "서울대학교", AdmissionResultStatus.PENDING);
        add(AdmissionType.EARLY, "서강대학교", AdmissionResultStatus.PENDING);
        em.flush();

        assertThat(service.suggest(null, "서").universities())
                .contains("서울대학교", "서강대학교");
        // 없으면 빈 목록이다 — 첫 해에는 아무것도 안 쌓여 있다
        assertThat(service.suggest(null, "없는대학").universities()).isEmpty();
    }
}
