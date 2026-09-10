package com.dlab.domain.holiday.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.holiday.entity.Holiday;
import com.dlab.domain.holiday.entity.HolidayType;
import com.dlab.domain.holiday.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 공휴일 등록 권한. <b>법정공휴일은 전 지점에 적용되므로</b> 지점 관리자가 넣으면
 * 다른 지점 급식까지 막힌다 — 그 경계를 검증한다.
 */
class HolidayServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 15);

    private HolidayRepository repository;
    private HolidayService service;

    private final AuthPrincipal headquarters = AuthPrincipal.of(
            1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    private final AuthPrincipal branchAdmin = AuthPrincipal.of(
            2L, "EMPLOYEE", 7L, List.of(Role.BRANCH_ADMIN), false);

    @BeforeEach
    void setUp() {
        repository = mock(HolidayRepository.class);
        given(repository.findNationwideInRange(any(), any())).willReturn(List.of());
        given(repository.findInRange(any(), any(), any())).willReturn(List.of());
        given(repository.save(any(Holiday.class))).willAnswer(inv -> inv.getArgument(0));
        // 등록 가능 연도 범위 판정이 "오늘"에 달려 있어 시각을 고정한다
        service = new HolidayService(repository,
                java.time.Clock.fixed(java.time.Instant.parse("2026-08-01T00:00:00Z"),
                        java.time.ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("본사는 전 지점 공휴일을 등록한다")
    void headquartersRegistersNationwide() {
        Holiday saved = service.register(headquarters, null, DATE, "광복절", HolidayType.PUBLIC, false);

        assertThat(saved.isNationwide()).isTrue();
        assertThat(saved.getHolidayType()).isEqualTo(HolidayType.PUBLIC);
    }

    @Test
    @DisplayName("★ 지점 관리자는 전 지점 공휴일을 등록할 수 없다 — 다른 지점 급식까지 막힌다")
    void branchAdminCannotRegisterNationwide() {
        assertThatThrownBy(() ->
                service.register(branchAdmin, null, DATE, "광복절", HolidayType.PUBLIC, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NATIONWIDE_HOLIDAY_FORBIDDEN);
    }

    @Test
    @DisplayName("지점 관리자는 자기 지점 자체휴일을 등록한다")
    void branchAdminRegistersOwnAcademyHoliday() {
        Holiday saved = service.register(branchAdmin, 7L, DATE, "개원기념일", HolidayType.ACADEMY, false);

        assertThat(saved.isNationwide()).isFalse();
        assertThat(saved.getAcademyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("★ 다른 지점에는 등록할 수 없다")
    void cannotRegisterToOtherAcademy() {
        assertThatThrownBy(() ->
                service.register(branchAdmin, 99L, DATE, "남의 지점", HolidayType.ACADEMY, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("지점 휴일에 법정공휴일 유형을 붙일 수 없다 — 같은 날이 지점마다 달라진다")
    void academyHolidayMustBeAcademyType() {
        assertThatThrownBy(() ->
                service.register(branchAdmin, 7L, DATE, "광복절", HolidayType.PUBLIC, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("같은 날짜 중복 등록은 막는다 — DB 제약 위반 대신 원인을 알려준다")
    void duplicateRejected() {
        given(repository.findNationwideInRange(DATE, DATE))
                .willReturn(List.of(Holiday.nationwide(DATE, "광복절", HolidayType.PUBLIC)));

        assertThatThrownBy(() ->
                service.register(headquarters, null, DATE, "중복", HolidayType.PUBLIC, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.HOLIDAY_DUPLICATED);
    }

    @Test
    @DisplayName("전 지점 공휴일과 같은 날 지점휴일은 공존한다")
    void academyHolidayCoexistsWithNationwide() {
        // 전 지점에 이미 광복절이 있어도 지점 조회 결과에 지점 것이 없으면 등록된다
        given(repository.findInRange(7L, DATE, DATE))
                .willReturn(List.of(Holiday.nationwide(DATE, "광복절", HolidayType.PUBLIC)));

        Holiday saved = service.register(branchAdmin, 7L, DATE, "개원기념일", HolidayType.ACADEMY, false);

        assertThat(saved.getAcademyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("★ 먼 미래 날짜는 거절한다 — 한 번 들어가면 화면에서 지울 수 없다")
    void rejectsFarFutureDate() {
        // 2099-01-01 이 그대로 저장되던 문제. 화면의 연도 필터에 안 잡혀
        // 조회도 삭제도 못 하고 API 로만 지울 수 있었다
        assertThatThrownBy(() -> service.register(
                headquarters, null, LocalDate.of(2099, 1, 1), "잘못된날짜",
                HolidayType.PUBLIC, false))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("다음 해까지는 등록된다 — 연말에 다음 해 공휴일을 미리 넣는 운영이 있다")
    void allowsNextYear() {
        Holiday saved = service.register(
                headquarters, null, LocalDate.of(2027, 1, 1), "신정",
                HolidayType.PUBLIC, false);

        assertThat(saved.getHolidayDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("지난 해도 등록된다 — 소급 등록이 있다")
    void allowsLastYear() {
        Holiday saved = service.register(
                headquarters, null, LocalDate.of(2025, 12, 25), "성탄절",
                HolidayType.PUBLIC, false);

        assertThat(saved.getHolidayDate()).isEqualTo(LocalDate.of(2025, 12, 25));
    }
}
