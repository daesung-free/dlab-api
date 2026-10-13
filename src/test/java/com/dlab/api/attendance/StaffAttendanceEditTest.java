package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.StaffAttendance;
import com.dlab.domain.attendance.entity.StaffAttendanceType;
import com.dlab.domain.attendance.service.StaffAttendanceService;
import com.dlab.domain.menu.entity.AccountMenu;
import com.dlab.domain.menu.repository.MenuRepository;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 출근 기록 보정 (긴급출입 2-4).
 *
 * <p>요구사항이 「수정 이력을 남깁니다」라고 하는데 <b>수정 기능 자체가 없었다.</b>
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>★ 권한을 명시적으로 켠 계정만 고친다</b> — 역할 기본값으로 판단하면 전원이 고칠 수 있다</li>
 *   <li><b>이유가 없으면 거절한다</b> — 「왜」가 없으면 「누가·언제·무엇을」이 쓸모없어진다</li>
 *   <li><b>근무일을 넘기는 수정을 거절한다</b> — 07:05 검사가 근무일로 세기 때문이다</li>
 *   <li><b>전후값이 둘 다 남는다</b> — 후값만 남기면 원본을 알 길이 없다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class StaffAttendanceEditTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 12);

    @Autowired StaffAttendanceService service;
    @Autowired MenuRepository menuRepository;
    @Autowired EntityManager em;
    @Autowired com.dlab.domain.user.service.ActorNames actorNames;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StaffAttendance record;
    AuthPrincipal editor;
    AuthPrincipal viewer;
    Long editorAccountId;

    @BeforeEach
    void setUp() {
        short year = (short) WORK_DATE.getYear();
        academy = new Academy("SE01", "출근수정지점", LocalTime.of(9, 0));
        em.persist(academy);

        Student person = new Student("SE-STAFF", "출근직원", "010-0000-0000");
        em.persist(person);
        StudentEnrollment staff = new StudentEnrollment(person, academy, year,
                "SE-0001", null, GradeType.STAFF);
        em.persist(staff);

        record = new StaffAttendance(staff, WORK_DATE, StaffAttendanceType.IN,
                WORK_DATE.atTime(7, 2).toInstant(ZoneOffset.UTC));
        em.persist(record);

        editor = principalWithEditPermission("se-editor");
        editorAccountId = editor.accountId();
        viewer = principalWithoutEditPermission("se-viewer");
        em.flush();
    }

    private AuthPrincipal principalWithEditPermission(String loginId) {
        Account account = staffAccount(loginId);
        var menu = menuRepository.findByCodes(
                List.of(StaffAttendanceService.EDIT_MENU_CODE)).get(0);
        em.persist(new AccountMenu(account, menu));
        em.flush();
        return AuthPrincipal.of(account.getId(), "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private AuthPrincipal principalWithoutEditPermission(String loginId) {
        Account account = staffAccount(loginId);
        em.flush();
        return AuthPrincipal.of(account.getId(), "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private Account staffAccount(String loginId) {
        Employee employee = new Employee(academy, "관리자" + loginId);
        em.persist(employee);
        Account account = Account.forEmployee(employee, loginId, "x", false);
        em.persist(account);
        return account;
    }

    @Test
    @DisplayName("보정하면 값이 바뀌고 전후값이 이력에 남는다")
    void editsAndKeepsHistory() {
        service.edit(editor, record.getId(), StaffAttendanceType.IN,
                WORK_DATE.atTime(6, 55), "지문 인식 지연으로 실제보다 늦게 찍힘");
        em.flush();

        assertThat(record.getRecordedAt())
                .isEqualTo(WORK_DATE.atTime(6, 55).atZone(
                        com.dlab.common.config.TimeConfig.KST).toInstant());

        var edits = service.editsOf(editor, record.getId());
        assertThat(edits).hasSize(1);
        // ★ 전후값이 둘 다 남아야 "무엇을 고쳤나"에 답할 수 있다
        assertThat(edits.get(0).getBeforeRecordedAt())
                .isEqualTo(WORK_DATE.atTime(7, 2).toInstant(ZoneOffset.UTC));
        assertThat(edits.get(0).getAfterRecordedAt()).isEqualTo(record.getRecordedAt());
        assertThat(edits.get(0).getReason()).contains("지문 인식 지연");
    }

    @Test
    @DisplayName("★★ 권한을 켜지 않은 계정은 고칠 수 없다 — 역할 기본값으로 보면 전원이 고친다")
    void requiresExplicitPermission() {
        // RoleMenuDefaults 는 「표에 없는 메뉴를 전 역할에 보인다」라
        // 그 규칙으로 판단하면 이 계정도 고칠 수 있게 된다
        assertThat(service.canEdit(viewer)).isFalse();
        assertThat(service.canEdit(editor)).isTrue();

        assertThatThrownBy(() -> service.edit(viewer, record.getId(),
                StaffAttendanceType.IN, WORK_DATE.atTime(6, 55), "사유"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("수정 권한이 없습니다");
    }

    @Test
    @DisplayName("★ 이유가 비면 거절한다 — 「왜」가 없으면 나머지가 쓸모없어진다")
    void requiresReason() {
        assertThatThrownBy(() -> service.edit(editor, record.getId(),
                StaffAttendanceType.IN, WORK_DATE.atTime(6, 55), "   "))
                .hasMessageContaining("이유를 적어");
    }

    @Test
    @DisplayName("★★ 근무일을 넘기는 수정은 거절한다 — 07:05 검사가 근무일로 센다")
    void rejectsCrossDayEdit() {
        assertThatThrownBy(() -> service.edit(editor, record.getId(),
                StaffAttendanceType.IN, WORK_DATE.minusDays(1).atTime(23, 50), "어제 것이었음"))
                .hasMessageContaining("근무일");
    }

    @Test
    @DisplayName("바뀐 것이 없으면 이력을 남기지 않는다 — 남기면 「고친 적 있다」가 거짓이 된다")
    void noopDoesNotRecord() {
        service.edit(editor, record.getId(), record.getEventType(),
                LocalDateTime.ofInstant(record.getRecordedAt(),
                        com.dlab.common.config.TimeConfig.KST),
                "확인만 함");
        em.flush();

        assertThat(service.editsOf(editor, record.getId())).isEmpty();
    }

    @Test
    @DisplayName("구분(출근↔퇴근)도 고칠 수 있다 — 이중 인식으로 뒤집힌 경우가 있다")
    void editsEventType() {
        service.edit(editor, record.getId(), StaffAttendanceType.OUT,
                WORK_DATE.atTime(7, 2), "퇴근인데 출근으로 찍힘");
        em.flush();

        assertThat(record.getEventType()).isEqualTo(StaffAttendanceType.OUT);
        assertThat(service.editsOf(editor, record.getId()).get(0).getBeforeEventType())
                .isEqualTo(StaffAttendanceType.IN);
    }

    @Test
    @DisplayName("★ 고쳐진 기록만 「보정됨」으로 표시된다 — 행마다 조회하지 않는다")
    void marksEditedRecords() {
        StaffAttendance untouched = new StaffAttendance(record.getEnrollment(), WORK_DATE,
                StaffAttendanceType.OUT, WORK_DATE.atTime(22, 10).toInstant(ZoneOffset.UTC));
        em.persist(untouched);
        em.flush();

        service.edit(editor, record.getId(), StaffAttendanceType.IN,
                WORK_DATE.atTime(6, 55), "보정");
        em.flush();

        var edited = service.editedIdsOf(List.of(record, untouched));
        assertThat(edited).containsExactly(record.getId());
    }

    @Test
    @DisplayName("★ 고친 사람 이름이 함께 나온다 — 계정 id 만으로는 화면에 쓸 수 없다")
    void resolvesEditorName() {
        // 이 화면을 지점 관리자도 보는데 그 계정에 계정 목록 조회 권한이 없을 수 있다.
        // 화면이 따로 맞춰야 하면 그 화면에서는 영원히 「-」가 된다
        var names = actorNames.of(java.util.List.of(editorAccountId));

        assertThat(names).containsEntry(editorAccountId, "관리자se-editor");
    }

    @Test
    @DisplayName("★ 배치·시스템이 한 것은 이름이 빈다 — 「사람이 아니다」를 그것으로 안다")
    void systemActorHasNoName() {
        // 시스템 계정(0)은 조회에서 아예 제외한다 —
        // null 로 두는 것과 「그냥 빠뜨렸다」가 섞이지 않게 한다
        assertThat(actorNames.of(java.util.List.of(
                com.dlab.common.config.SecurityAuditorAware.SYSTEM_ACCOUNT_ID))).isEmpty();
    }

    @Test
    @DisplayName("다른 지점 기록은 고칠 수 없다")
    void cannotEditOtherBranch() {
        Academy other = new Academy("SE02", "남의지점", LocalTime.of(9, 0));
        em.persist(other);
        em.flush();
        AuthPrincipal otherEditor = principalWithEditPermission("se-other");
        // 지점을 남의 것으로 바꿔 끼운다
        AuthPrincipal scoped = AuthPrincipal.of(otherEditor.accountId(), "EMPLOYEE",
                other.getId(), List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> service.edit(scoped, record.getId(),
                StaffAttendanceType.IN, WORK_DATE.atTime(6, 55), "사유"))
                .isInstanceOf(BusinessException.class);
    }
}
