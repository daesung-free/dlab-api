package com.dlab.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.notification.service.GuardianRecipientResolver;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentGuardianLink;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자 수신자 해석.
 *
 * <h2>★★ 앱을 설치하지 않은 보호자에게도 보낸다</h2>
 * 그전까지 모든 경로가 <b>학부모 앱 계정</b>만 찾았다. 신입생 동의서가 보호자 번호를
 * 필수로 수집하고, 가입·관리자 화면에서 <b>계정 없이 번호만</b> 들어올 수 있게 되면서
 * 그 전제가 깨졌다. ⚠️ 특히 <b>미등원은 끌 수 없는 필수 알림</b>이라 수신처가 없으면
 * 무단결석을 아무도 모르게 된다.
 *
 * <p>지키려는 것 — <b>번호만 있어도 수신자가 될 것</b>,
 * <b>한 보호자에게 두 번 가지 않을 것</b>, <b>부·모 양쪽에 갈 것</b>.
 */
@SpringBootTest
@Transactional
class GuardianRecipientResolverTest {

    @Autowired EntityManager em;
    @Autowired GuardianRecipientResolver resolver;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Student student;

    @BeforeEach
    void setUp() {
        Academy academy = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(academy);
        student = new Student("GR-0001", "김학생", "010-1111-0000");
        em.persist(student);
        em.flush();
    }

    private ParentGuardian link(String name, String phone, boolean withAccount) {
        ParentGuardian guardian = new ParentGuardian(name, phone, null);
        em.persist(guardian);
        em.persist(new StudentGuardianLink(student, guardian, (short) 1));
        if (withAccount) {
            em.persist(Account.forGuardian(guardian, phone, passwordEncoder.encode("pw-12345678")));
        }
        em.flush();
        return guardian;
    }

    @Test
    @DisplayName("★★ 번호만 있는 보호자도 수신자다 — 고치기 전에는 아무에게도 안 갔다")
    void phoneOnlyGuardianIsRecipient() {
        link("학부모", "010-7777-1111", false);
        em.clear();

        assertThat(resolver.of(student.getId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.account()).as("계정은 없다").isNull();
                    assertThat(r.phone()).isEqualTo("010-7777-1111");
                    assertThat(r.hasPhone()).isTrue();
                    assertThat(r.guardianId()).as("멱등키에 쓸 식별자는 있다").isNotNull();
                });
    }

    @Test
    @DisplayName("★★ 계정이 있는 보호자에게 두 번 보내지 않는다 — 번호로도 걸려서 두 통이 된다")
    void accountGuardianIsNotDuplicated() {
        link("김보호", "010-7777-2222", true);
        em.clear();

        assertThat(resolver.of(student.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.account()).as("계정 쪽이 쓰인다").isNotNull());
    }

    @Test
    @DisplayName("★ 부·모 양쪽에 간다 — 한쪽만 앱을 깔아도 둘 다 받는다")
    void bothParentsReceive() {
        link("아버지", "010-7777-3333", true);
        link("어머니", "010-7777-4444", false);
        em.clear();

        assertThat(resolver.of(student.getId()))
                .hasSize(2)
                .anySatisfy(r -> assertThat(r.account()).isNotNull())
                .anySatisfy(r -> assertThat(r.account()).isNull())
                .allSatisfy(r -> assertThat(r.hasPhone()).isTrue());
    }

    @Test
    @DisplayName("보호자가 없으면 비어 있다 — 예외를 던지지 않는다")
    void noGuardianIsEmpty() {
        assertThat(resolver.of(student.getId())).isEmpty();
    }
}
