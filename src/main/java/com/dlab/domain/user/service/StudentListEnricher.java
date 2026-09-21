package com.dlab.domain.user.service;

import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.master.repository.ScholarshipRepository;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 학생 목록에 <b>다른 테이블에 있는 표시값</b>(반·담임·좌석·장학)을 붙인다.
 *
 * <h2>★ 존재 이유는 N+1 방지 하나다</h2>
 * 반·좌석·담임·장학은 전부 등록 건과 1:N인 별도 테이블이라, 응답을 만들면서 행마다 조회하면
 * <b>쿼리가 학생 수만큼 나간다.</b> 재원생 전체(수백 명)가 대상인 화면이라 그대로 두면
 * 목록 한 번에 수백 쿼리가 나가고, 지점이 커질수록 조용히 느려진다.
 *
 * <p>그래서 <b>등록 건 ID를 모아 IN 조회 3번</b>으로 끝내고 {@code Map}으로 만들어 붙인다 —
 * 학생이 20명이든 500명이든 쿼리 수가 같다. 키오스크 학생 목록
 * ({@code KioskStudentQueryService.studentList})과 출결 현황
 * ({@code AttendanceBoardService.board})이 이미 쓰는 방식을 그대로 따랐다.
 *
 * <p>반 이름과 담임은 <b>같은 조회</b>에서 나온다 — 담임은 학생이 아니라 반에 붙으므로
 * ({@code class_master.homeroom_teacher}) 배정을 fetch join 하면 추가 쿼리가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentListEnricher {

    private final ClassAssignmentRepository classAssignmentRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final ScholarshipRepository scholarshipRepository;

    /**
     * 등록 건 목록에 붙일 표시값을 <b>한 번에</b> 모은다.
     *
     * <p>목록이 비면 조회하지 않는다 — 빈 {@code IN ()}은 DB에 따라 문법 오류가 되거나
     * 전건을 훑는다.
     */
    public Extras of(List<StudentEnrollment> enrollments) {
        if (enrollments == null || enrollments.isEmpty()) {
            return Extras.empty();
        }
        List<Long> ids = enrollments.stream().map(StudentEnrollment::getId).toList();

        Map<Long, String> classNames = new HashMap<>();
        Map<Long, ClassAssignment> fixedAssignments = new HashMap<>();
        for (ClassAssignment a : classAssignmentRepository.findActiveFixedByEnrollmentIds(ids)) {
            classNames.put(a.getEnrollment().getId(), a.getClassMaster().getName());
            fixedAssignments.put(a.getEnrollment().getId(), a);
        }
        // ★ 담임은 "그 학생의 담임"(예외 지정 ?? 반 담임)이다 — 반 배정이 없어도 예외 지정은 있을 수 있다
        Map<Long, String> homeroomTeachers = new HashMap<>();
        for (StudentEnrollment e : enrollments) {
            Teacher homeroom = HomeroomResolver.of(e, fixedAssignments.get(e.getId()));
            if (homeroom != null) {
                homeroomTeachers.put(e.getId(), homeroom.getName());
            }
        }

        Map<Long, String> seats = new HashMap<>();
        seatAssignmentRepository.findActiveByEnrollmentIds(ids)
                .forEach(a -> seats.put(a.getEnrollment().getId(), a.getSeat().getSeatCd()));

        // 장학은 한 학생에게 여러 건이 붙을 수 있다 — 하나만 고르면 화면마다 다른 값이 보인다
        Map<Long, List<String>> scholarships = new HashMap<>();
        scholarshipRepository.findByEnrollmentIds(ids).forEach(s -> scholarships
                .computeIfAbsent(s.getEnrollment().getId(), k -> new ArrayList<>())
                .add(s.getScholarshipType()));

        return new Extras(classNames, homeroomTeachers, seats, scholarships);
    }

    /** 단건(상세·접수·수정 응답)용. 목록과 같은 필드를 채우기 위한 편의 메서드다. */
    public Extras of(StudentEnrollment enrollment) {
        return of(List.of(enrollment));
    }

    /**
     * 등록 건 ID → 표시값.
     *
     * <p>배정이 없는 학생은 <b>키가 아예 없다</b>({@code null}이 아니라 미등록) —
     * 미배정과 "반 이름이 빈 문자열"을 구분하려면 조회 결과에 없는 것이 맞다.
     */
    public record Extras(
            Map<Long, String> classNames,
            Map<Long, String> homeroomTeachers,
            Map<Long, String> seatCodes,
            Map<Long, List<String>> scholarshipTypes
    ) {

        public static Extras empty() {
            return new Extras(Map.of(), Map.of(), Map.of(), Map.of());
        }

        public String className(Long enrollmentId) {
            return classNames.get(enrollmentId);
        }

        public String homeroomTeacher(Long enrollmentId) {
            return homeroomTeachers.get(enrollmentId);
        }

        public String seatCd(Long enrollmentId) {
            return seatCodes.get(enrollmentId);
        }

        /** 장학이 없으면 빈 목록. {@code null}을 내리면 화면이 매번 방어해야 한다. */
        public List<String> scholarships(Long enrollmentId) {
            return scholarshipTypes.getOrDefault(enrollmentId, List.of());
        }
    }
}
