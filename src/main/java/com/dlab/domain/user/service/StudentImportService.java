package com.dlab.domain.user.service;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelReader;
import com.dlab.common.excel.ExcelRow;
import com.dlab.common.excel.ImportPreview;
import com.dlab.common.excel.RowError;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 학생 엑셀 일괄 업로드 (요구사항 F-4.1-2 · 실행가이드 P1-02).
 *
 * <p>여기는 1년 단위 코호트라 매년 초에 수백 명이 한꺼번에 들어온다. 한 명씩 폼으로 넣는 건
 * 현실적이지 않고, 명단은 원래 엑셀로 오간다. 컷오버 때 기존 재원생 이관도 이 경로다.
 *
 * <p><b>미리보기와 반영을 나눈다.</b> 올리자마자 쓰지 않고 "총 120행 중 117행 정상, 3행 오류"를
 * 먼저 보여준다. 그리고 <b>오류행이 있어도 정상행은 반영한다</b> — 3건 틀렸다고 전부 되돌리면
 * 사용자가 파일을 고쳐 올릴 때마다 오류를 하나씩만 발견하게 된다(실행가이드 완료기준).
 *
 * <p><b>미리보기 결과를 서버에 저장하지 않는다.</b> 반영할 때 파일을 다시 받아 같은 파싱을
 * 한 번 더 돌린다. 세션에 들고 있으면 다중 인스턴스에서 어느 서버가 받을지 모르고,
 * 미리보기와 반영 사이에 원본 데이터가 바뀌었을 때 낡은 판정으로 쓰게 된다.
 *
 * <p><b>★ 마스킹된 값은 "변경 없음"으로 읽는다.</b> 가장 흔한 흐름이 "내려받아 수정 후
 * 재업로드"인데 내려받은 파일의 연락처는 이미 {@code 010-****-1234}다. 그대로 저장하면
 * 전 학생 연락처가 날아가고, 화면에는 원래도 마스킹돼 보여서 한동안 발견되지 않는다.
 * 신규 등록에서는 유지할 원래 값이 없으므로 <b>오류</b>로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentImportService {

    /**
     * 엑셀 컬럼 정의. <b>위치가 아니라 헤더명으로 찾는다</b> — 학원마다 열 순서가 다르고
     * 중간에 열이 끼기도 한다. 별칭을 넉넉히 받아 파일을 되돌려보내는 일을 줄인다.
     */
    static final ColumnMapping MAPPING = ColumnMapping.builder()
            .required("name", "이름", "성명", "학생명")
            .required("grade", "학년", "구분")
            .optional("uniqueCode", "학생고유ID", "고유ID", "학생ID")
            .optional("phone", "연락처", "휴대폰", "전화번호", "핸드폰")
            .optional("track", "계열", "과정")
            .optional("birthDate", "생년월일", "생일")
            .optional("gender", "성별")
            .optional("schoolName", "출신학교", "학교", "고교");

    private final ExcelReader excelReader;
    private final StudentRepository studentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AcademyRepository academyRepository;
    private final StudentService studentService;

    /** 한 행을 읽어 검증까지 끝낸 결과. 반영 단계에서 그대로 쓴다. */
    public record ParsedStudent(
            int rowNumber,
            String uniqueCode,
            String name,
            String phone,
            LocalDate birthDate,
            String gender,
            String schoolName,
            GradeType grade,
            TrackType track,
            boolean existing) {
    }

    /** 미리보기 — <b>아무것도 쓰지 않는다.</b> */
    @Transactional(readOnly = true)
    public ImportPreview<ParsedStudent> preview(InputStream file, Long academyId, short year,
                                                AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return parse(file, academyId, year);
    }

    /**
     * 반영 — 정상행만 저장한다.
     *
     * @return 미리보기와 같은 형태. 무엇이 들어가고 무엇이 걸렸는지 화면이 그대로 보여준다
     */
    @Transactional
    public ImportPreview<ParsedStudent> importStudents(InputStream file, Long academyId, short year,
                                                       AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        ImportPreview<ParsedStudent> parsed = parse(file, academyId, year);
        for (ParsedStudent row : parsed.valid()) {
            apply(academy, year, row);
        }
        log.info("학생 일괄 업로드: academy={}, year={}, 반영 {}건 / 오류 {}행",
                academyId, year, parsed.validRows(), parsed.errorRows());
        return parsed;
    }

    private ImportPreview<ParsedStudent> parse(InputStream file, Long academyId, short year) {
        ExcelReader.ExcelParseResult result = excelReader.read(file, MAPPING);

        List<ParsedStudent> valid = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();

        for (ExcelRow row : result.rows()) {
            ParsedStudent parsed = toStudent(row, academyId, year);
            if (row.hasError()) {
                errors.addAll(row.errors());
            } else {
                valid.add(parsed);
            }
        }
        return ImportPreview.of(null, result.rows().size(), valid, errors);
    }

    private ParsedStudent toStudent(ExcelRow row, Long academyId, short year) {
        String name = row.requiredText("name", "이름");
        GradeType grade = grade(row);
        TrackType track = track(row);

        String uniqueCode = row.text("uniqueCode");
        boolean existing = uniqueCode != null && findExisting(uniqueCode, academyId, year).isPresent();

        // ★ 마스킹된 값은 "안 건드렸다"로 읽는다. 기존 학생이면 null(=변경 없음),
        //   신규면 유지할 원래 값이 없으므로 오류다.
        String phone = maskAware(row, "phone", "연락처", existing);
        String schoolName = maskAware(row, "schoolName", "출신학교", existing);
        String maskedAwareName = existing ? maskAware(row, "name", "이름", true) : name;

        return new ParsedStudent(
                row.rowNumber(), uniqueCode, maskedAwareName, phone,
                row.date("birthDate", "생년월일"), row.text("gender"), schoolName,
                grade, track, existing);
    }

    /**
     * 마스킹을 감안해 값을 읽는다.
     *
     * @return 마스킹된 값이면 {@code null}(변경 없음). 신규 등록이면 대신 오류를 쌓는다
     */
    private String maskAware(ExcelRow row, String field, String label, boolean existing) {
        String value = row.text(field);
        if (!Masking.isMasked(value)) {
            return value;
        }
        if (!existing) {
            row.addError(field, label + "이(가) 마스킹된 값입니다. 신규 등록에는 실제 값이 필요합니다. (입력값: "
                    + value + ")");
            return null;
        }
        return null;
    }

    private GradeType grade(ExcelRow row) {
        String raw = row.requiredText("grade", "학년");
        if (raw == null) {
            return null;
        }
        // 운영에서 쓰는 표기를 그대로 받는다 — 내부 enum 값(HIGH2/HIGH3/N_SU)을 외우게 하지 않는다
        return switch (raw.replaceAll("\\s+", "")) {
            case "고2", "HIGH2", "2학년" -> GradeType.HIGH2;
            case "고3", "HIGH3", "3학년" -> GradeType.HIGH3;
            case "N수생", "n수생", "N수", "재수생", "성인", "N_SU" -> GradeType.N_SU;
            default -> {
                row.addError("grade", "학년은 고2·고3·N수생 중 하나여야 합니다. (입력값: " + raw + ")");
                yield null;
            }
        };
    }

    private TrackType track(ExcelRow row) {
        String raw = row.text("track");
        if (raw == null) {
            return null;
        }
        return switch (raw.replaceAll("\\s+", "")) {
            case "자연", "이과", "SCIENCE" -> TrackType.SCIENCE;
            case "인문", "문과", "HUMANITIES" -> TrackType.HUMANITIES;
            case "예체능", "예술", "ART" -> TrackType.ART;
            case "공통", "COMMON" -> TrackType.COMMON;
            default -> {
                row.addError("track", "계열은 자연·인문·예체능·공통 중 하나여야 합니다. (입력값: " + raw + ")");
                yield null;
            }
        };
    }

    /**
     * 한 행 반영.
     *
     * <p><b>고유ID로 기존 학생을 찾으면 등록 건만 갱신한다</b> — 새 사람으로 만들면 상담
     * 이력·신상기록부가 끊긴다. 여기는 삼수 재등록이 흔해서 동일인 추적이 실제로 필요하다.
     */
    private void apply(Academy academy, short year, ParsedStudent row) {
        Optional<StudentEnrollment> existing = row.uniqueCode() == null
                ? Optional.empty()
                : findExisting(row.uniqueCode(), academy.getId(), year);

        if (existing.isPresent()) {
            StudentEnrollment enrollment = existing.get();
            enrollment.getStudent().updateProfile(
                    row.name(), row.phone(), row.birthDate(), row.gender(), row.schoolName(), null);
            enrollment.updateEnrollment(row.grade(), row.track(), null);
            return;
        }
        // 신규 — 학번 채번은 StudentService가 한다(충돌 시 재시도 포함)
        studentService.admitParsed(academy, year, row.name(), row.phone(),
                row.birthDate(), row.gender(), row.schoolName(), null, row.grade(), row.track());
    }

    private Optional<StudentEnrollment> findExisting(String uniqueCode, Long academyId, short year) {
        return studentRepository.findByUniqueCode(uniqueCode)
                .flatMap(student -> enrollmentRepository
                        .findByStudentIdAndAcademyIdAndYearAndDeletedFalse(student.getId(), academyId, year));
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
