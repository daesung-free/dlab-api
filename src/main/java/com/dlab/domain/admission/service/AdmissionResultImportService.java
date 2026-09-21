package com.dlab.domain.admission.service;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelReader;
import com.dlab.common.excel.ExcelRow;
import com.dlab.common.excel.ImportPreview;
import com.dlab.common.excel.RowError;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.admission.entity.AdmissionResult;
import com.dlab.domain.admission.entity.AdmissionResultStatus;
import com.dlab.domain.admission.entity.AdmissionSource;
import com.dlab.domain.admission.entity.AdmissionType;
import com.dlab.domain.admission.repository.AdmissionResultRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실적 엑셀 일괄 등록.
 *
 * <h2>학번으로 학생을 찾고 이름으로 한 번 더 맞춘다</h2>
 * 학번 한 자리 오타면 다른 학생 실적으로 들어간다. 이름 칸이 있으면 대조해서 다르면 그 행을 막는다.
 *
 * <h2>반영 중에 실패하지 않게 미리 거른다</h2>
 * 정원(수시 6 · 정시 3)을 넘거나 이미 같은 지원(구분·대학·학과)이 있으면 <b>미리보기 단계에서</b>
 * 오류로 잡는다. 반영하다가 한 행이 예외를 던지면 트랜잭션이 통째로 되돌아가 정상행까지 사라진다.
 * 같은 파일을 두 번 올려도 이미 있는 지원은 오류로 빠져 중복이 쌓이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionResultImportService {

    static final ColumnMapping MAPPING = ColumnMapping.builder()
            .required("studentNo", "학번")
            .required("type", "구분", "수시/정시", "전형구분")
            .required("university", "대학명", "대학")
            .required("department", "학과명", "학과", "모집단위")
            .optional("name", "이름", "성명", "학생명")
            .optional("track", "전형명", "전형")
            .optional("result", "결과", "합격여부", "합불")
            .optional("memo", "메모", "비고");

    private final ExcelReader excelReader;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AdmissionResultRepository resultRepository;

    /** 반영될(또는 반영된) 한 행. */
    public record ParsedResult(int rowNumber, Long enrollmentId, String studentNo,
                               String studentName, AdmissionType admissionType,
                               String universityName, String departmentName, String trackName,
                               AdmissionResultStatus result, String memo) {
    }

    /** 미리보기 — 아무것도 쓰지 않는다. */
    @Transactional(readOnly = true)
    public ImportPreview<ParsedResult> preview(AuthPrincipal me, Long academyId, InputStream file) {
        return parse(me.requireAcademyScope(academyId), file);
    }

    /** 반영 — 정상행만 넣는다. 결과는 미리보기와 같은 모양이다. */
    @Transactional
    public ImportPreview<ParsedResult> importResults(AuthPrincipal me, Long academyId,
                                                     InputStream file) {
        Long scope = me.requireAcademyScope(academyId);
        ImportPreview<ParsedResult> parsed = parse(scope, file);
        Map<Long, StudentEnrollment> enrollments = enrollmentRepository.findAllById(
                        parsed.valid().stream().map(ParsedResult::enrollmentId).distinct().toList())
                .stream().collect(Collectors.toMap(StudentEnrollment::getId, Function.identity()));
        for (ParsedResult row : parsed.valid()) {
            resultRepository.save(new AdmissionResult(enrollments.get(row.enrollmentId()),
                    row.admissionType(), row.universityName(), row.departmentName(),
                    row.trackName(), row.result(), AdmissionSource.STAFF, row.memo()));
        }
        log.info("실적 일괄 등록: academy={}, 반영 {}건 / 오류 {}행",
                scope, parsed.validRows(), parsed.errorRows());
        return parsed;
    }

    private ImportPreview<ParsedResult> parse(Long academyId, InputStream file) {
        if (academyId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        ExcelReader.ExcelParseResult read = excelReader.read(file, MAPPING);
        Map<String, StudentEnrollment> byStudentNo = enrollmentRepository
                .findCurrentByAcademyId(academyId).stream()
                .filter(e -> e.getStudentNo() != null)
                .collect(Collectors.toMap(StudentEnrollment::getStudentNo, Function.identity(),
                        (a, b) -> a));

        // 정원·중복 판정 — 기존 건 + 이 파일에서 앞서 받은 행을 함께 센다
        Map<String, Integer> countByStudentType = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Map<Long, List<AdmissionResult>> existing = new HashMap<>();

        List<ParsedResult> valid = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (ExcelRow row : read.rows()) {
            ParsedResult parsed = toResult(row, byStudentNo);
            if (!row.hasError()) {
                List<AdmissionResult> mine = existing.computeIfAbsent(parsed.enrollmentId(),
                        resultRepository::findByEnrollmentId);
                String typeKey = parsed.enrollmentId() + ":" + parsed.admissionType();
                int already = countByStudentType.computeIfAbsent(typeKey, k -> (int) mine.stream()
                        .filter(r -> r.getAdmissionType() == parsed.admissionType()).count());
                String dupKey = parsed.enrollmentId() + ":" + parsed.admissionType() + ":"
                        + parsed.universityName() + ":" + parsed.departmentName();
                boolean duplicate = !seen.add(dupKey) || mine.stream().anyMatch(r ->
                        r.getAdmissionType() == parsed.admissionType()
                                && r.getUniversityName().equals(parsed.universityName())
                                && r.getDepartmentName().equals(parsed.departmentName()));
                if (duplicate) {
                    row.addError("university", "이미 등록된 지원입니다(같은 구분·대학·학과).");
                } else if (already >= parsed.admissionType().limit()) {
                    row.addError("type", "%s는 %d개까지 등록합니다.".formatted(
                            parsed.admissionType().displayName(), parsed.admissionType().limit()));
                } else {
                    countByStudentType.put(typeKey, already + 1);
                }
            }
            if (row.hasError()) {
                errors.addAll(row.errors());
            } else {
                valid.add(parsed);
            }
        }
        return ImportPreview.of(null, read.rows().size(), valid, errors);
    }

    private ParsedResult toResult(ExcelRow row, Map<String, StudentEnrollment> byStudentNo) {
        String studentNo = row.requiredText("studentNo", "학번");
        AdmissionType type = type(row);
        String university = row.requiredText("university", "대학명");
        String department = row.requiredText("department", "학과명");
        AdmissionResultStatus result = result(row);

        StudentEnrollment enrollment = studentNo == null ? null : byStudentNo.get(studentNo);
        if (studentNo != null && enrollment == null) {
            row.addError("studentNo", "재원 중인 학생의 학번이 아닙니다: " + studentNo);
        }
        String name = row.text("name");
        if (enrollment != null && name != null
                && !name.equals(enrollment.getStudent().getName())) {
            // 학번 오타로 다른 학생 실적이 들어가는 것을 막는다
            row.addError("name", "학번 %s 의 학생 이름과 다릅니다.".formatted(studentNo));
        }
        return new ParsedResult(row.rowNumber(), enrollment == null ? null : enrollment.getId(),
                studentNo, enrollment == null ? name : enrollment.getStudent().getName(),
                type, university, department, row.text("track"), result, row.text("memo"));
    }

    private AdmissionType type(ExcelRow row) {
        String raw = row.requiredText("type", "구분");
        if (raw == null) {
            return null;
        }
        return switch (raw.replace(" ", "").toUpperCase()) {
            case "수시", "EARLY" -> AdmissionType.EARLY;
            case "정시", "REGULAR" -> AdmissionType.REGULAR;
            default -> {
                row.addError("type", "구분은 수시 또는 정시입니다: " + raw);
                yield null;
            }
        };
    }

    /** 비어 있으면 발표 전이다 — 불합격으로 읽으면 실적이 실제보다 낮게 잡힌다. */
    private AdmissionResultStatus result(ExcelRow row) {
        String raw = row.text("result");
        if (raw == null) {
            return AdmissionResultStatus.PENDING;
        }
        return switch (raw.replace(" ", "").toUpperCase()) {
            case "합격", "최초합격", "추가합격", "PASSED" -> AdmissionResultStatus.PASSED;
            case "불합격", "FAILED" -> AdmissionResultStatus.FAILED;
            case "발표전", "대기", "미발표", "PENDING" -> AdmissionResultStatus.PENDING;
            case "등록포기", "포기", "GAVE_UP" -> AdmissionResultStatus.GAVE_UP;
            default -> {
                row.addError("result", "결과는 합격·불합격·발표전·등록포기 중 하나입니다: " + raw);
                yield null;
            }
        };
    }
}
