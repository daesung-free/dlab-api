package com.dlab.domain.grade.service;

import com.dlab.domain.grade.entity.MockExamStudentKey;
import com.dlab.domain.grade.repository.MockExamStudentKeyRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 연구소 파일의 행 → 우리 학생.
 *
 * <p>★ <b>성적·정오표·답안표가 같은 규칙을 써야 한다.</b> 파일마다 매칭을 따로 짜면 한 학생이
 * 성적 파일에서는 잡히고 정오표에서는 빠지는 일이 생긴다 — 그러면 앱에 점수는 있는데
 * 채점이 비어 뜬다.
 *
 * <p>순서: <b>외부생 제외 → 연결 키(학교코드·반·번호) → 이름</b>. 동명이인은 고르지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MockExamStudentMatcher {

    /**
     * 외부생 학교코드. 재원생이 아니다(연구소 확정). 받은 세트에 이 파일이 들어 있어
     * 건너뛴 건수를 알려야 한다 — 조용히 버리면 "왜 인원이 다르냐" 가 된다.
     */
    public static final String EXTERNAL_SCHOOL_CODE = "99709";

    private final MockExamStudentKeyRepository keyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    /** 한 번의 업로드 동안 쓸 매칭표. 행마다 조회하지 않는다. */
    public Table prepare(Long academyId, short year) {
        Map<String, StudentEnrollment> byKey = keyRepository.findAllByScope(academyId, year).stream()
                .collect(Collectors.toMap(
                        k -> fileKey(k.getSchoolCode(), k.getClassNo(), k.getStudentNo()),
                        MockExamStudentKey::getEnrollment, (a, b) -> a));
        Map<String, List<StudentEnrollment>> byName = enrollmentRepository
                .findCurrentByAcademyId(academyId).stream()
                .collect(Collectors.groupingBy(e -> normalize(e.getStudent().getName()),
                        LinkedHashMap::new, Collectors.toList()));
        return new Table(byKey, byName);
    }

    /**
     * 파일 식별자 한 덩어리. 세 값을 다 쓴다 — 번호만으로는 반이 다른 학생과 겹치고, 학교코드를
     * 빼면 지점이 섞인다. 값이 비면 키를 만들지 않는다(빈 문자열끼리 맞아 엉뚱한 학생에게 붙는다).
     */
    public static String fileKey(String schoolCode, String classNo, String studentNo) {
        if (isBlank(schoolCode) || isBlank(classNo) || isBlank(studentNo)) {
            return null;
        }
        return schoolCode.trim() + "|" + classNo.trim() + "|" + studentNo.trim();
    }

    static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    public record Table(Map<String, StudentEnrollment> byKey,
                        Map<String, List<StudentEnrollment>> byName) {

        public Match match(String schoolCode, String classNo, String studentNo, String name) {
            if (EXTERNAL_SCHOOL_CODE.equals(schoolCode == null ? null : schoolCode.trim())) {
                return Match.ofExternal();
            }
            // 사람이 한 번 정해준 연결이 먼저다 — 이름은 동명이인에서 멈춘다
            StudentEnrollment linked = byKey.get(fileKey(schoolCode, classNo, studentNo));
            if (linked != null) {
                return Match.found(linked);
            }
            List<StudentEnrollment> candidates = byName.getOrDefault(normalize(name), List.of());
            if (candidates.isEmpty()) {
                return Match.notFound("이 지점 재원생 중에 같은 이름이 없습니다.");
            }
            if (candidates.size() > 1) {
                // 동명이인을 임의로 고르면 남의 성적이 들어간다 — 사람이 정해야 한다
                return Match.notFound("같은 이름이 %d명입니다. 학생을 지정하면 다음 회차부터 자동으로 연결됩니다."
                        .formatted(candidates.size()));
            }
            return Match.found(candidates.get(0));
        }
    }

    /** @param enrollment 찾았으면 값이 있다 · @param external 외부생이라 건너뛴다 */
    public record Match(StudentEnrollment enrollment, boolean external, String reason) {
        static Match found(StudentEnrollment e) {
            return new Match(e, false, null);
        }

        static Match ofExternal() {
            return new Match(null, true, null);
        }

        static Match notFound(String reason) {
            return new Match(null, false, reason);
        }
    }
}
