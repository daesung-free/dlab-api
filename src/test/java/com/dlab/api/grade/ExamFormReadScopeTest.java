package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.service.ExamFormAdminService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.EntityManager;
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
 * 성적 양식 <b>읽기</b> 범위 — 공통 행을 지점 소속도 볼 수 있는가.
 *
 * <h2>왜 테스트로 고정하나</h2>
 * 쓰기 검사({@code requireScope})를 읽기에도 쓰고 있었더니 <b>담임이 성적 화면을 아예 못 썼다.</b>
 * 양식이 거의 전부 공통 행이라 {@code academyId}를 비우면 「본사만」에 걸려 403이고,
 * 자기 지점을 넣으면 그 행들이 걸러져 <b>0건</b>이 온다 — 어느 쪽으로도 표의 열을 만들 수 없다.
 * 운영에서 네 조합을 다 눌러 확인된 증상이라, 되돌아가지 않게 못박아 둔다.
 *
 * <p>함께 지키는 것 — <b>쓰기는 열리지 않는다.</b> 읽기만 풀면서 같은 메서드를 쓰면
 * 지점이 공통 행을 고칠 수 있게 되고, 그러면 나머지 열 지점 가입 화면이 같이 바뀐다.
 */
@SpringBootTest
@Transactional
class ExamFormReadScopeTest {

    @Autowired ExamFormAdminService adminService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    AuthPrincipal teacher;
    AuthPrincipal otherBranchTeacher;
    Academy bundang;
    Academy mokdong;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        mokdong = new Academy("45", "목동", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(mokdong);
        em.flush();
        teacher = new AuthPrincipal(10L, "teacher1", bundang.getId(),
                Set.of(Role.TEACHER), false, false);
        otherBranchTeacher = new AuthPrincipal(11L, "teacher2", mokdong.getId(),
                Set.of(Role.TEACHER), false, false);
    }

    @Test
    @DisplayName("★ 담임이 지점을 비우고 부르면 공통 양식이 내려온다 — 전에는 403이었다")
    void teacherReadsCommonForms() {
        assertThat(adminService.list(teacher, (short) 2026, null)).isNotEmpty();
    }

    @Test
    @DisplayName("과목 기본 구성도 같이 열린다 — 표의 열이 여기서 나온다")
    void teacherReadsPresets() {
        assertThat(adminService.presets(teacher, (short) 2026, GradeType.N_SU, null)).isNotEmpty();
    }

    @Test
    @DisplayName("★ 쓰기는 그대로 막힌다 — 지점이 공통 행을 고치면 열 지점 가입 화면이 같이 바뀐다")
    void teacherCannotWriteCommonForms() {
        assertThatThrownBy(() -> adminService.rollover(teacher, null, (short) 2026, (short) 2027))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본사만");
    }

    @Test
    @DisplayName("남의 지점 양식은 읽기도 막힌다 — 읽기를 풀었다고 지점 경계가 사라지는 건 아니다")
    void teacherCannotReadOtherBranch() {
        assertThatThrownBy(() -> adminService.list(otherBranchTeacher, (short) 2026, bundang.getId()))
                .isInstanceOf(BusinessException.class);
    }
}
