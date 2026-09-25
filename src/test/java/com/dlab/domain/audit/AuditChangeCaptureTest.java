package com.dlab.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
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
 * 감사 로그의 "무엇이 바뀌었는지" (F-C-1).
 *
 * <p>지금까지 <b>직원 계정 변경에만</b> 전후값이 남고 나머지는 비어 있었다 —
 * JPA 콜백이 변경 전 값을 주지 않아서다. Hibernate 인터셉터가 그걸 채운다.
 */
@SpringBootTest
@Transactional
class AuditChangeCaptureTest {

    @Autowired EntityManager em;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-A-1", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, year, "2026-0001", "RF-A", GradeType.N_SU);
        em.persist(minji);
        em.flush();
    }

    private List<AuditLog> logsOf(String entityType, Long id) {
        em.flush();
        return auditLogRepository.findByEntity(entityType, id);
    }

    @Test
    @DisplayName("★ 학생 등록을 고치면 바뀐 값이 전→후로 남는다")
    void recordsBeforeAndAfter() {
        minji.assignCard("RF-B");
        em.flush();

        String changes = logsOf("학생 등록", minji.getId()).stream()
                .map(AuditLog::getChanges)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        assertThat(changes)
                .as("변경 전→후가 비어 있으면 '누가 언제'만 남아 다툼을 가릴 수 없다")
                .isNotNull()
                .contains("rfidNo")
                .contains("RF-A")
                .contains("RF-B");
    }

    @Test
    @DisplayName("바뀌지 않은 필드와 자동 갱신 컬럼은 남기지 않는다")
    void keepsOnlyRealChanges() {
        minji.assignCard("RF-C");
        em.flush();

        String changes = logsOf("학생 등록", minji.getId()).stream()
                .map(AuditLog::getChanges).filter(java.util.Objects::nonNull).findFirst().orElseThrow();

        // updatedAt 은 모든 수정에 따라붙어 진짜 변경을 덮는다
        assertThat(changes).doesNotContain("updatedAt");
        assertThat(changes).doesNotContain("studentNo");
    }

    @Test
    @DisplayName("★ 학생에 붙는 기록은 지점이 없어도 등록 건에서 지점을 찾아 남긴다")
    void resolvesAcademyFromEnrollment() {
        PenaltyItem item = new PenaltyItem(bundang, year, "지각", 5, PenaltyCategory.DEMERIT);
        em.persist(item);
        PenaltyPoint point = new PenaltyPoint(bundang, minji, item, -5, "지각",
                PenaltySource.MANUAL, null);
        em.persist(point);
        em.flush();

        assertThat(logsOf("상벌점", point.getId()))
                .isNotEmpty()
                .allSatisfy(log -> assertThat(log.getAcademyId()).isEqualTo(bundang.getId()));
    }
}
