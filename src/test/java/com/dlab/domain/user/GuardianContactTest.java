package com.dlab.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.GuardianContactService;
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

/**
 * 보호자 연락처 관리.
 *
 * <h2>★★ 이 경로가 없으면 학부모 알림톡이 한 통도 못 나간다</h2>
 * 수신처는 보호자 번호이고, 지금까지 <b>학부모가 앱에 가입할 때만</b> 들어왔다 —
 * 미등원 알림은 끌 수 없는 필수 알림인데 수신처가 없는 상태가 된다.
 *
 * <p>지키려는 것 — <b>형제자매가 같은 보호자를 공유할 것</b>(번호 UNIQUE),
 * <b>마스킹된 값이 저장되지 않을 것</b>, <b>연결만 끊고 보호자는 남을 것</b>,
 * <b>다른 지점 학생을 건드리지 못할 것</b>.
 */
@SpringBootTest
@Transactional
class GuardianContactTest {

    @Autowired EntityManager em;
    @Autowired GuardianContactService service;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment first;
    StudentEnrollment sibling;
    StudentEnrollment other;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(8, 20));
        ilsan = new Academy("32", "일산", LocalTime.of(8, 20));
        em.persist(bundang);
        em.persist(ilsan);

        first = enroll(bundang, "GC-0001", "9001", "첫째");
        sibling = enroll(bundang, "GC-0002", "9002", "둘째");
        other = enroll(ilsan, "GC-0003", "9003", "다른지점");
        em.flush();
    }

    private StudentEnrollment enroll(Academy academy, String code, String no, String name) {
        Student student = new Student(code, name, "010-1111-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(student, academy, (short) 2026,
                no, "C" + no, GradeType.HIGH3);
        em.persist(e);
        return e;
    }

    private AuthPrincipal staff(Academy academy) {
        return new AuthPrincipal(0L, "EMPLOYEE", academy.getId(),
                Set.of(Role.BRANCH_ADMIN), false, false);
    }

    @Test
    @DisplayName("보호자 연락처를 넣을 수 있다 — ★ 이 경로가 없어서 알림톡 수신처를 만들 방법이 없었다")
    void canAddGuardian() {
        service.add(staff(bundang), first.getId(), "김보호", "010-7777-1111", (short) 1, false);
        em.flush();

        assertThat(service.list(staff(bundang), first.getId()))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.getGuardian().getPhone()).isEqualTo("010-7777-1111");
                    assertThat(l.getGuardian().getName()).isEqualTo("김보호");
                });
    }

    @Test
    @DisplayName("★★ 형제자매는 같은 보호자를 공유한다 — 번호에 UNIQUE 가 걸려 있어 새로 만들면 터진다")
    void siblingsShareOneGuardian() {
        service.add(staff(bundang), first.getId(), "김보호", "010-7777-2222", (short) 1, false);
        service.add(staff(bundang), sibling.getId(), "김보호", "010-7777-2222", (short) 1, false);
        em.flush();
        em.clear();

        Long count = em.createQuery("""
                SELECT count(g) FROM ParentGuardian g WHERE g.phone = :phone
                """, Long.class).setParameter("phone", "010-7777-2222").getSingleResult();
        assertThat(count).as("보호자 행은 하나다").isEqualTo(1L);

        assertThat(service.list(staff(bundang), first.getId())).hasSize(1);
        assertThat(service.list(staff(bundang), sibling.getId())).hasSize(1);
    }

    @Test
    @DisplayName("★★ 마스킹된 값은 저장하지 않는다 — 알림톡이 아무에게도 안 가는데 화면은 정상으로 보인다")
    void rejectsMaskedValues() {
        assertThatThrownBy(() -> service.add(staff(bundang), first.getId(),
                "김보호", "010-****-1234", (short) 1, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("마스킹");

        assertThatThrownBy(() -> service.add(staff(bundang), first.getId(),
                "김*수", "010-7777-3333", (short) 1, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("마스킹");
    }

    @Test
    @DisplayName("같은 보호자를 두 번 연결하지 않는다")
    void rejectsDuplicateLink() {
        service.add(staff(bundang), first.getId(), "김보호", "010-7777-4444", (short) 1, false);
        em.flush();

        assertThatThrownBy(() -> service.add(staff(bundang), first.getId(),
                "김보호", "010-7777-4444", (short) 2, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 연결된");
    }

    @Test
    @DisplayName("★ 연결만 끊고 보호자는 남긴다 — 다른 자녀가 쓰고 있고 지난 알림 이력이 그 번호를 가리킨다")
    void unlinkKeepsGuardian() {
        service.add(staff(bundang), first.getId(), "김보호", "010-7777-5555", (short) 1, false);
        service.add(staff(bundang), sibling.getId(), "김보호", "010-7777-5555", (short) 1, false);
        em.flush();

        Long guardianId = service.list(staff(bundang), first.getId())
                .get(0).getGuardian().getId();
        service.unlink(staff(bundang), first.getId(), guardianId);
        em.flush();

        assertThat(service.list(staff(bundang), first.getId())).isEmpty();
        assertThat(service.list(staff(bundang), sibling.getId()))
                .as("다른 자녀의 연결은 그대로다").hasSize(1);
        assertThat(em.find(ParentGuardian.class, guardianId))
                .as("보호자 행은 남아 있다").isNotNull();
    }

    @Test
    @DisplayName("★★ 다른 지점 학생의 보호자는 건드릴 수 없다")
    void cannotTouchOtherAcademy() {
        assertThatThrownBy(() -> service.add(staff(bundang), other.getId(),
                "김보호", "010-7777-6666", (short) 1, false))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.list(staff(bundang), other.getId()))
                .isInstanceOf(BusinessException.class);
    }
}
