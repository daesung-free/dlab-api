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
        service = new HolidayService(repository);
    }

    @Test
    @DisplayName("본사는 전 지점 공휴일을 등록한다")
    void headquartersRegistersNationwide() {
        Holiday saved = service.register(headquarters, null, DATE, "광복절", HolidayType.PUBLIC);

        assertThat(saved.isNationwide()).isTrue();
        assertThat(saved.getHolidayType()).isEqualTo(HolidayType.PUBLIC);
    }

    @Test
    @DisplayName("★ 지점 관리자는 전 지점 공휴일을 등록할 수 없다 — 다른 지점 급식까지 막힌다")
    void branchAdminCannotRegisterNationwide() {
        assertThatThrownBy(() ->
                service.register(branchAdmin, null, DATE, "광복절", HolidayType.PUBLIC))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NATIONWIDE_HOLIDAY_FORBIDDEN);
    }

    @Test
    @DisplayName("지점 관리자는 자기 지점 자체휴일을 등록한다")
    void branchAdminRegistersOwnAcademyHoliday() {
        Holiday saved = service.register(branchAdmin, 7L, DATE, "개원기념일", HolidayType.ACADEMY);

        assertThat(saved.isNationwide()).isFalse();
        assertThat(saved.getAcademyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("★ 다른 지점에는 등록할 수 없다")
    void cannotRegisterToOtherAcademy() {
        assertThatThrownBy(() ->
                service.register(branchAdmin, 99L, DATE, "남의 지점", HolidayType.ACADEMY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("지점 휴일에 법정공휴일 유형을 붙일 수 없다 — 같은 날이 지점마다 달라진다")
    void academyHolidayMustBeAcademyType() {
        assertThatThrownBy(() ->
                service.register(branchAdmin, 7L, DATE, "광복절", HolidayType.PUBLIC))
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
                service.register(headquarters, null, DATE, "중복", HolidayType.PUBLIC))
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

        Holiday saved = service.register(branchAdmin, 7L, DATE, "개원기념일", HolidayType.ACADEMY);

        assertThat(saved.getAcademyId()).isEqualTo(7L);
    }
}
