package com.dlab.api.admin;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.event.entity.AnnualEvent;
import com.dlab.domain.event.repository.AnnualEventRepository;
import com.dlab.domain.event.service.AnnualEventService;
import com.dlab.domain.file.service.FileStorage;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.BillingStandard;
import com.dlab.domain.payment.repository.BillingStandardRepository;
import com.dlab.domain.payment.service.BillingStandardService;
import com.dlab.domain.qna.entity.QnaOfflineReservation;
import com.dlab.domain.qna.entity.QnaOfflineSlot;
import com.dlab.domain.qna.service.QnaOfflineService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 연간 행사·청구 기준 개별 전년도 복사 · 질의응답 예약의 과목·사진. */
@SpringBootTest
@Transactional
class CopyYearAndQnaTest {

    @Autowired AnnualEventService eventService;
    @Autowired AnnualEventRepository eventRepository;
    @Autowired BillingStandardService standardService;
    @Autowired BillingStandardRepository standardRepository;
    @Autowired QnaOfflineService qnaService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;
    @MockitoBean FileStorage storage;

    Academy bundang;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        admin = new AuthPrincipal(1L, "branch", bundang.getId(), Set.of(Role.BRANCH_ADMIN), false, false);
    }

    @Test
    @DisplayName("★ 연간 행사만 따로 복사한다 — 두 번 눌러도 두 벌이 되지 않는다")
    void copyAnnualEvents() {
        eventRepository.save(new AnnualEvent((short) 2093, bundang.getId(), "여름 설명회",
                LocalDate.of(2093, 7, 10), LocalDate.of(2093, 7, 11), null, true, null));
        em.flush();

        var first = eventService.copyYear(admin, bundang.getId(), (short) 2093, (short) 2094);
        var second = eventService.copyYear(admin, bundang.getId(), (short) 2093, (short) 2094);
        em.flush();

        assertThat(first.copied()).isEqualTo(1);
        assertThat(second.copied()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(eventRepository.findAllOfYear((short) 2094, bundang.getId()))
                .extracting(AnnualEvent::getStartDate).containsExactly(LocalDate.of(2094, 7, 10));
    }

    @Test
    @DisplayName("★ 청구 기준만 따로 복사한다 — 같은 코드는 건너뛰고 사용 중지 상태도 옮긴다")
    void copyBillingStandards() {
        BillingStandard fee = BillingStandard.fixed(bundang, (short) 2093, "EXAM_FEE",
                BillingItemType.values()[0], "모의고사비", null, 12000, null, null, (short) 1, null);
        BillingStandard old = BillingStandard.fixed(bundang, (short) 2093, "OLD_FEE",
                BillingItemType.values()[0], "옛 항목", null, 5000, null, null, (short) 2, null);
        old.changeActive(false);
        standardRepository.save(fee);
        standardRepository.save(old);
        em.flush();

        var first = standardService.copyYear(admin, bundang.getId(), (short) 2093, (short) 2094);
        var second = standardService.copyYear(admin, bundang.getId(), (short) 2093, (short) 2094);
        em.flush();

        assertThat(first.copied()).isEqualTo(2);
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(standardRepository.findByCode((short) 2094, "OLD_FEE", bundang.getId()))
                .get().extracting(BillingStandard::isActive).isEqualTo(false);
    }

    @Test
    @DisplayName("★ 질의응답 예약에 과목과 사진이 붙는다 — 사진은 본인 예약에만, 3장까지")
    void qnaSubjectAndPhotos() {
        when(storage.presignedGetUrl(anyString(), any())).thenReturn("https://example.invalid/photo");
        Student s = new Student("DL-Q", "질문학생", "010-0000-0000");
        em.persist(s);
        StudentEnrollment me = new StudentEnrollment(s, bundang, (short) 2026, "0001", null, GradeType.N_SU);
        em.persist(me);
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        QnaOfflineSlot slot = new QnaOfflineSlot(bundang, (short) tomorrow.getYear(), tomorrow,
                LocalTime.of(15, 0), LocalTime.of(15, 30), null, "상담실", (short) 2);
        em.persist(slot);
        em.flush();

        QnaOfflineReservation r = qnaService.reserve(slot.getId(), me.getId(), "3번 문제", "수학");
        assertThat(r.getSubject()).isEqualTo("수학");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        for (int i = 0; i < 3; i++) {
            qnaService.addPhoto(r.getId(), me.getId(), "q" + i + ".png", "image/png", png);
        }
        em.flush();
        assertThatThrownBy(() -> qnaService.addPhoto(r.getId(), me.getId(), "q4.png", "image/png", png))
                .isInstanceOf(BusinessException.class);

        var photos = qnaService.photosOf(List.of(r.getId())).get(r.getId());
        assertThat(photos).hasSize(3);
        assertThat(photos.get(0).url()).isNotBlank();

        // 남의 예약에는 못 올린다
        Student other = new Student("DL-Q2", "다른학생", "010-0000-0001");
        em.persist(other);
        StudentEnrollment them = new StudentEnrollment(other, bundang, (short) 2026, "0002", null, GradeType.N_SU);
        em.persist(them);
        em.flush();
        assertThatThrownBy(() -> qnaService.addPhoto(r.getId(), them.getId(), "x.png", "image/png", png))
                .isInstanceOf(BusinessException.class);

        qnaService.deletePhoto(r.getId(), me.getId(), photos.get(0).attachmentId());
        em.flush();
        assertThat(qnaService.photosOf(List.of(r.getId())).get(r.getId())).hasSize(2);
    }
}
