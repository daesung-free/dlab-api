package com.dlab.domain.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.admission.entity.CommonCode;
import com.dlab.domain.admission.repository.CommonCodeRepository;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입학신청 코드 시드 ({@code V20261022_1200}).
 *
 * <h2>★★ 운영에서 둘 다 비어 있었다 — 그래서 모든 접수가 901 이었다</h2>
 * 2026-10-22 운영 실측에서 {@code academy.dlab_cd} 가 11개 지점 전부 {@code NULL} 이고
 * {@code common_code} 가 0건이었다. 그런데 <b>연동 규격서는 시너지에 「분당 = F」를
 * 주고 있었다</b> — 자격증명을 넣어도 <b>모든 신청이 「알 수 없는 학원코드」로 거부</b>된다.
 *
 * <p>★ <b>이것은 코드로는 드러나지 않는 종류의 결함이다.</b> 엔드포인트도 있고 테스트도
 * 통과하는데 <b>데이터가 없어서</b> 안 되는 것이고, 밖에서는 「API 가 안 된다」로만 보인다.
 * 그래서 데이터가 들어 있는지를 테스트로 고정한다.
 */
@SpringBootTest
@Transactional
class AdmissionCodeSeedTest {

    /** 출처: {@code DSA_DLab_연동_정의서 v1.2} (2026-01-30), 대성전산 부여. */
    private static final Map<String, String> EXPECTED = Map.of(
            "31", "F", "32", "I", "33", "T", "34", "P", "42", "C",
            "43", "E", "44", "J", "45", "O", "46", "A");

    @Autowired AcademyRepository academyRepository;
    @Autowired CommonCodeRepository commonCodeRepository;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    @Test
    @DisplayName("★★ 과목 5종이 들어 있다 — 비면 홈페이지 성적 입력이 과목을 못 그린다")
    void subjectCodesAreSeeded() {
        var subjects = commonCodeRepository.findAllOfGroup(CommonCode.GRP_SUBJECT);

        assertThat(subjects).hasSize(5);
        // ★ idx 가 과목 자리다 — 성적 입력(setStdTest)의 subject 가 이 값을 쓴다
        assertThat(subjects).extracting(c -> c.getIdx() + ":" + c.getName())
                .containsExactly("1:국어", "2:수학", "3:영어", "4:탐구1", "5:탐구2");
        // 지점 구분이 없다 — 규격서 3.6 이 acid 를 받지 않는다
        assertThat(subjects).allSatisfy(c -> assertThat(c.getAcademy()).isNull());
    }

    @Test
    @DisplayName("★★ 운영 지점에 입학신청 코드(dlab_cd)가 들어 있다 — 비면 접수가 전부 901")
    void admissionCodesAreSeededForRealBranches() {
        // ⚠️ 테스트 DB 에는 운영 지점이 없을 수 있다(테스트가 자기 지점을 만든다).
        //    그 경우 검사할 대상이 없으므로 넘긴다 — 시드를 돌린 환경에서 의미가 있다.
        for (var entry : EXPECTED.entrySet()) {
            academyRepository.findByAcadCdAndDeletedFalse(entry.getKey()).ifPresent(a ->
                    assertThat(a.getDlabCd())
                            .as("%s(%s) 의 입학신청 코드", a.getAcadNm(), entry.getKey())
                            .isEqualTo(entry.getValue()));
        }
    }

    @Test
    @DisplayName("⚠️ 대전·대구는 비어 있는 것이 맞다 — 대성전산이 코드를 아직 안 줬다")
    void daejeonAndDaeguHaveNoCodeYet() {
        for (String acadCd : new String[]{"47", "48"}) {
            academyRepository.findByAcadCdAndDeletedFalse(acadCd).ifPresent(a ->
                    assertThat(a.getDlabCd())
                            .as("%s — ★ 값을 추측해서 넣지 말 것. 어긋난 코드가 들어가면 "
                                    + "접수가 엉뚱한 지점으로 들어가고 원인을 찾기 어렵다. "
                                    + "비어 있으면 901 이 나서 바로 드러난다", a.getAcadNm())
                            .isNull());
        }
    }
}
