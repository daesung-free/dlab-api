# D.Lab 전체 도메인 엔티티 설계 (수정본 — student/enrollment 일관성 정리)

**정본 우선순위**: D.Lab 통합기획서(PRD) > CLAUDE.md(요약) > 이 문서(설계 초안).

**패키지**: `domain/{user, attendance, penalty, approval, schedule, payment, meal, grade, notice, firewall, chat, notification, ...}`

※ `approval`과 `penalty`는 각각 `firewall`·`attendance` 안에 두지 않는다.

**이번 수정 요약**
1. `class_assignment`(A4) / `scholarship`(A7) / `seat_assignment`(M)이 `student_id`+`reg_year`를 따로 갖던 걸 `enrollment_id` 하나로 통합 — A1이 정한 "연도 종속 데이터는 enrollment를 참조" 규칙 위반이었음
2. 관계도(트리)와 실제 테이블 정의가 어긋나 있던 것 정리 — 도메인별로 `student_id`(사람 단위) vs `enrollment_id`(등록 건 단위) 판단해서 통일. 판단 기준은 각 섹션에 명시
3. `reg_year`/`year` 컬럼명 통일 — §0-1 기준 `year`로 통일 (enrollment로 흡수된 곳은 컬럼 자체 삭제)
4. `student_enrollment.rfid_no`에 부분 유니크 인덱스 추가 (`WHERE is_current = true`) — 없으면 두 학생이 같은 카드를 갖는 걸 DB가 못 막음
5. 선생님(`TB_TEACHER_MST`) vs 직원(`직원관리_TB`) 분리 이슈는 **아직 결정 안 된 사안**이라 구조를 임의로 바꾸지 않고 N섹션에 open item으로만 표시

---

## 0. 전 테이블 공통 규칙 (설계 시작 전 반드시 적용)

### 0-1. 공통 컬럼 — 예외 없이 모든 테이블

| 컬럼 | 타입 | 왜 필요한가 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | **다지점.** 신규 테이블 만들 때 가장 자주 빠뜨린다 |
| year | smallint | **1년 단위 코호트**(독학재수)라 거의 모든 데이터가 연도에 종속. "전년도 복사"의 기준. **`student_enrollment`를 FK로 갖는 테이블은 이 컬럼을 따로 두지 않는다** — enrollment가 이미 연도를 내포하므로 중복 |
| created_at / updated_at | timestamptz | |
| **created_by** | bigint (account_id) | **감사로그. 보안심사 직결 항목** |
| **is_deleted** | boolean DEFAULT false | soft delete |

> ⚠️ `created_by`·`is_deleted`를 나중에 붙이면 **그 이전 기간의 이력은 영영 복구할 수 없다.**
>
> 의도적 예외는 사유를 반드시 명시한다 — `parent_guardian`(지점 종속이 아니라 자녀를 따라감), `track_master`·`role`(지점 무관 고정 참조값), `student`(사람 자체는 지점·연도 무관, A1 참고).

### 0-2. 전년도 복사 (YearlySnapshotService)

```
department → course_type → class_group → curriculum → penalty_item → tuition
```

**이 순서를 지켜야 하고, 단순 `INSERT SELECT` 금지** — FK가 깨진다.

### 0-3. 개인정보

- 전화·주소·생년월일 포함 응답은 **상위 관리자만** 조회
- **엑셀 Export 마스킹 기본 ON** (`010-****-1234`)
- 발주처가 개인정보 유출 조사·벌금 사례로 민감도가 높다 — 임의 완화 금지

### 0-4. ★ student_id vs enrollment_id 판단 기준 (이번 수정에서 신규 정리)

| 기준 | 참조 | 예시 |
| --- | --- | --- |
| **그 해 등록/활동에 종속되는 데이터** → `enrollment_id` | `student_enrollment` | 반배정, 장학, 좌석배정, 청구·수납, 급식신청, 학습계획, 데일리루틴, 출결, 승인요청, 특강신청, 내신·가채점, 실적 |
| **사람에 영구히 붙는 데이터** → `student_id` | `student` | 상담이력, 신상기록부, 모의고사 성적(재수생의 연속 추이를 보려는 의도적 예외) |

> 애매하면 "이 데이터가 삼수생의 1년차 기록과 2년차 기록을 구분해야 하는가"로 판단한다. 구분해야 하면 `enrollment_id`.

---

## A. domain/user — 회원관리

### A0. account — 로그인 계정

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| account_type | varchar(10) NOT NULL CHECK IN ('STUDENT','PARENT','EMPLOYEE','TEACHER') | JWT role claim 기준 — 선생님/직원 분리로 `TEACHER` 추가 |
| login_id | varchar(50) UNIQUE NOT NULL | 전화번호(학생·학부모) 또는 사번(직원·선생님) |
| password_hash | varchar(255), nullable | |
| status | varchar(20) NOT NULL DEFAULT 'PENDING' CHECK IN ('PENDING','ACTIVE','SUSPENDED','WITHDRAWN') | 학생: PENDING→ACTIVE 이분법(중간상태 없음). 학부모: 즉시 ACTIVE |
| student_id / guardian_id / employee_id / **teacher_id** | bigint, FK, nullable (넷 중 정확히 하나만 NOT NULL) | `CHECK (num_nonnulls(...) = 1)` |
| last_login_at | timestamptz | |
| created_at/updated_at | timestamptz | |

> Refresh Token/Access Token 블랙리스트/로그인 rate limiting은 **Redis**에서 처리.

**가입 흐름 — 학생과 학부모가 완전히 다르다**

| | 학생 | 학부모 |
| --- | --- | --- |
| 인증 | 휴대폰 인증 | 휴대폰 인증 |
| 추가 입력 | — | **학생 고유ID**(`student.unique_code`) |
| 승인 | **관리자 승인 필요.** PENDING→ACTIVE 이분법 | **없음 — 즉시 ACTIVE** |

> **학생 가입은 휴대폰번호로 중복을 차단한다.** PENDING 상태의 기존 요청까지 함께 검사해야 한다.
>
> 이 규칙은 **신상기록부와 무관하다**(S 참고).
>
> 학부모-자녀 관계는 **검증하지 않는다** — 클라이언트가 인지·감수한 리스크, 추가 검증 로직 임의로 만들지 말 것.

### A1. ★ 학생 = "사람 + 등록 건" 2단 구조

> **한 테이블로 만들면 안 된다.** 레거시 실측(`TB_STUDENT_MST` / `TB_PDTL_RLS`)에서 확인된 구조:
> - 학번·RFID 카드번호는 사람이 아니라 "등록 건"에 붙는다 → 학번 매년 초기화가 구조적으로 보장, 카드 재사용돼도 과거 기수 출결과 안 섞임
> - 독학재수라 **1년 단위 코호트** — 연도를 넘는 학생 연속성을 가정하지 말 것
> - 삼수로 재등록하면 같은 사람에 등록 행만 추가 → 동일인 추적이 공짜로 됨 (상담 이력의 "입학예약 상담부터 연속 기록" 요구가 이걸로 성립)

### A1-1. student — 사람 (영구, 연도 무관)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | 내부 영구 식별자 |
| unique_code | varchar(20) UNIQUE NOT NULL | **학생 고유ID** — 마이페이지 상시 노출, 학부모 가입 시 이 값+전화번호로 즉시 연결. 재등록해도 안 바뀜 |
| name | varchar(20) | |
| phone | varchar(20) | |
| birth_date | date | ⚠️ 암호화 여부 미정 — 동명이인 구분 조회조건으로 쓰이면 인덱스를 못 탄다. 조회 용도 확인 후 결정 |
| gender | varchar(1) CHECK IN ('M','F') | |
| school_name | varchar(64) | 출신고 — 사람 속성 |
| search_name_normalized | varchar(20), 인덱스 | 검색·정렬 대응 |
| created_at / updated_at / created_by / is_deleted | | §0-1 |

> `academy_id`·`year` 없음 — 지점과 연도는 등록 건의 속성(의도된 예외).

### A1-2. student_enrollment — 등록 건 (기수별, 1인 N행)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| student_id | bigint, FK → student | |
| academy_id | bigint | 지점. 기수마다 다를 수 있다 |
| year | smallint | 기수(등록년도) |
| student_no | varchar(20) | 학번 — 매년 초기화. `UNIQUE(academy_id, year, student_no)` |
| rfid_no | varchar(10) | 키오스크 카드 태깅 매칭 키. DSA 호환 엔드포인트 13개가 조회키로 사용 |
| is_current | boolean DEFAULT true | **현재 유효한 등록 건 플래그**(레거시 `STAT_GB='T'` 대응) |
| grade | varchar(10) CHECK IN ('HIGH2','HIGH3','N_SU') | 고2/고3/N수생 3분류 |
| track | varchar(10) CHECK IN ('HUMANITIES','SCIENCE','ART','COMMON') | |
| enrollment_status | varchar(20) NOT NULL DEFAULT 'ENROLLED' CHECK IN ('ENROLLED','ON_LEAVE','WITHDRAWN','GRADUATED') | 재원 상태 — `account.status`와 별개 |
| admission_date / withdrawal_date | date | |
| created_at / updated_at / created_by / is_deleted | | §0-1 |

> **`rfid_no`는 전역 UNIQUE가 아니다** — 등록 건마다 쌓이는 이력이라서. 대신 **부분 유니크 인덱스**로 "현재 유효한 등록 건 안에서는 카드번호가 겹치면 안 된다"를 강제한다:
> ```sql
> CREATE UNIQUE INDEX ux_enrollment_current_rfid
>   ON student_enrollment (academy_id, rfid_no)
>   WHERE is_current = true;
> ```
> 이게 없으면 데이터 입력 실수로 두 학생이 같은 카드번호를 갖는 걸 DB가 못 막고, 카드로 학생을 찾을 때도 **반드시 `is_current = true`로 걸러야** 퇴원생 카드가 태깅을 통과하는 사고를 막는다.
>
> **이 문서 전체에서 `enrollment_id(FK)`라고 적힌 것은 전부 이 테이블을 가리킨다.** 판단 기준은 §0-4 참고.

### A2. parent_guardian — 학부모

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| name | varchar(20) | |
| phone | varchar(20) UNIQUE | |
| gender | varchar(1) CHECK IN ('F','M') | `getParentHpList`의 `p_gb` 대응 |
| created_at | timestamptz | |

> academy_id 없음 — 학부모는 지점 종속이 아니라 자녀를 따라감(의도된 예외).

### A3. student_guardian_link — 다자녀 연결

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| student_id | bigint, FK → student | |
| guardian_id | bigint, FK → parent_guardian | |
| relation_order | smallint | |
| PRIMARY KEY (student_id, guardian_id) | | |

### A4. class_assignment — 반 배정 + 담임 자동 연동 *(수정: enrollment_id로 통합)*

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | |
| **enrollment_id** | bigint, FK → student_enrollment | ~~student_id + reg_year~~ → 연도가 이미 enrollment에 내포되므로 통합 |
| class_id | bigint, FK → class_master | 담임은 `class_master.homeroom_teacher_id`로 자동 결정 |
| class_type | varchar(10) CHECK IN ('FIXED','MOVING') | |
| assigned_at | timestamptz | |
| is_active | boolean DEFAULT true | |

### A5. (이동) 상벌점 → `domain/penalty` 로 분리

**`attendance`나 `user` 하위에 두지 말 것.** 규칙엔진은 출결과 데일리루틴 양쪽에서 트리거된다(I-5). → **V 섹션 참고.**

### A6. admission_waitlist / admission_reservation — 대기자·입학예약

| 테이블 | 컬럼 | 비고 |
| --- | --- | --- |
| admission_reservation | id, academy_id, name, student_phone, parent_phone, birth_date(암호화), desired_admission_date, consultation_note, created_at | |
| admission_waitlist | id, reservation_id(FK), waitlist_no, status(WAITING/NOTIFIED/CONVERTED), notified_at, converted_student_id(FK, nullable) | |

### A7. scholarship — 장학생 *(수정: enrollment_id로 통합)*

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| **enrollment_id** | bigint, FK → student_enrollment | ~~student_id + reg_year~~ → 통합 |
| scholarship_type | varchar(20) | |
| discount_rate | numeric(5,2) | |

---

## B. domain/payment — 수납+급식 결제 공용 (D.Lab 자체 PG사 = "대성 PG")

> **확정**: 대성전산 수납 프로그램 사용 안 함. 수납·급식 결제 전부 이 공용 도메인에서 처리.

### B1. billing_policy — 청구 기준 (상품 마스터)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | |
| product_type | varchar(20) CHECK IN ('TUITION','SPECIAL_LECTURE','DORM','MEAL','BOOK','OTHER') | |
| name | varchar(64) | |
| amount | numeric(12,0) | |
| tax_applicable | boolean | |
| valid_from/valid_to | date | |
| is_active | boolean | |

### B2. billing — 청구 발행 *(수정: enrollment_id로 통합, reg_year 삭제)*

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | |
| **enrollment_id** | bigint, FK → student_enrollment | ~~student_id + reg_year~~ → 그 해 등록건에 매인 금액이므로 통합 |
| billing_policy_id | bigint, FK → billing_policy | |
| total_amount / discount_amount / target_amount / received_amount | numeric(12,0) | |
| status | varchar(20) CHECK IN ('UNPAID','PARTIAL','PAID','CANCELLED') | |
| created_at | timestamptz | |

### B3. payment_receipt — 수납 내역

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | |
| billing_id | bigint, FK → billing | |
| payment_method | varchar(20) CHECK IN ('CARD','VIRTUAL_ACCOUNT','CASH') | |
| amount | numeric(12,0) | |
| pg_transaction_id | varchar(64), nullable | |
| paid_at | timestamptz | |

### B4. virtual_account — 가상계좌

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | |
| billing_id | bigint, FK → billing | |
| bank_name | varchar(64) | |
| account_no | varchar(64), **암호화 대상** | |
| valid_from/valid_to | date | |
| is_used | boolean | |

### B5. refund — 환불/취소

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | |
| billing_id | bigint, FK → billing | |
| payment_receipt_id | bigint, FK → payment_receipt | |
| refund_method | varchar(20) CHECK IN ('CARD_CANCEL','ACCOUNT_TRANSFER','CASH') | |
| refund_amount | numeric(12,0) | |
| bank_name/account_no | varchar, **암호화 대상** | |
| approved_at | timestamptz | |
| created_by | varchar(32) | |

---

## C. domain/attendance — 출결·벌점·자리이탈

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| kiosk_device | id, academy_id, location, device_type(ATTENDANCE/MEAL) | |
| attendance_tagging_log | id, academy_id, enrollment_id(FK), event_type(7종, C-1 참고), source(KIOSK_NFC/APP_QR), kiosk_device_id(FK, nullable), period_id(FK → period_master, nullable), recorded_at | `ABSENT` 없음 |
| attendance_daily_status | id, academy_id, enrollment_id(FK), attendance_date, final_status CHECK IN ('PRESENT','LATE','ABSENT','EARLY_LEAVE'), calculated_at | 파생 일자 상태. `ABSENT`는 오직 여기에만 |
| absence_reason | id, academy_id, enrollment_id(FK), attendance_date, reason_type(ABSENCE/LATE/EARLY_LEAVE/OUTING), reason_text, submitted_at, deadline_at(당일 자정), **approval_request_id**(FK → approval_request) | 승인 상태는 `approval_request`(E2)에서 읽음. ⚠️ "벌점 확정 후 승인 불가"의 확정 기준 미정(I-10) |
| seat_leave_record | id, academy_id, **enrollment_id**(FK), seat_id(FK → seat_master, nullable), trigger_source(KIOSK_CARD/APP_REQUEST), status(LEFT/RETURNED), left_at, returned_at, kiosk_device_id(FK, nullable) | *(수정: student_id→enrollment_id — 그 해 좌석배정에 종속)*. ⚠️ 두 트리거 경로 충돌 규칙 미정, 실시간 좌석표 UI는 2차 개발 이관 제안 상태 |
| (미등원 알림) | 별도 테이블 아님 | 등원시간 정각 스케줄러 → 미등원 필터링 → 카카오 알림톡. 결과는 `notification_log`(K) |

### C-1. ★ 출결 이벤트 7종 — DSA 원본 코드를 그대로 저장한다

| `att_gn` | 의미 | 비고 |
| --- | --- | --- |
| `S` | 등원 | |
| `T` | 하원 | 순공시간 계산의 필수 짝 |
| `A` | 지각 | |
| `D` | 외출 | 일반 외출 |
| `N` | 사유 외출 | `D`와 구분 — 벌점 판정이 갈림 |
| `C` | 조퇴 | |
| `R` | 복귀 | 외출/조퇴 후 복귀 |

> ⚠️ `ABSENT`를 이 enum에 넣지 말 것 — 파생 상태값, `attendance_daily_status`에만 존재.

---

## D. domain/schedule — 정기일정

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| regular_schedule | id, academy_id, **enrollment_id**(FK), apply_month, schedule_type(현강/과외), day_of_week, start_time, end_time, location_note, registered_at | *(수정: student_id→enrollment_id)*. 매월 1일 등록 |
| ~~schedule_approval~~ | **별도 테이블 두지 말 것** | `approval_request`(E2)에 위임 — `approver_type='PARENT'` + `escalation_approver_type=NULL` |
| schedule_variance_log | id, regular_schedule_id(FK), actual_check_in_at, variance_minutes, is_recognized | |

---

## E. domain/approval — ★ 공통 승인 라우팅

> **`firewall` 안에 두지 말 것.** 사유신청·정기일정·방화벽을 하나의 라우팅 엔진에 태운다(F-4.11-5).

### E1. approval_item — 승인 항목 마스터

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id / year | | §0-1 |
| request_type | varchar(30) CHECK IN ('FIREWALL_UNLOCK','ABSENCE_REASON','REGULAR_SCHEDULE') | |
| approver_type | varchar(10) CHECK IN ('PARENT','TEACHER','AUTO') | 1차 승인 주체 |
| timeout_minutes | smallint, nullable | NULL이면 에스컬레이션 없음 |
| escalation_approver_type | varchar(10), nullable | |

### E2. approval_request — 승인 요청 건

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id / year | | §0-1 |
| approval_item_id | bigint, FK → approval_item | |
| enrollment_id | bigint, FK → student_enrollment | 신청 학생 |
| status | varchar(20) CHECK IN ('PENDING','APPROVED','REJECTED','CANCELED') | |
| requested_at | timestamptz | |
| timeout_minutes | smallint NOT NULL | 신청 시점 스냅샷 |
| escalation_at | timestamptz NOT NULL | `requested_at + timeout_minutes` |
| escalation_target_employee_id | bigint, FK → employee, nullable | 신청 시점 담당선생님 스냅샷 |
| resolved_at | timestamptz, nullable | |
| resolver_type | varchar(10) CHECK IN ('PARENT','TEACHER'), nullable | |
| resolver_id | bigint, nullable | |
| resolution_case | varchar(30) CHECK IN ('PARENT_IN_TIME','STAFF_AFTER_TIMEOUT','STAFF_BEFORE_TIMEOUT'), nullable | |
| reject_reason | varchar(500), nullable | |

### E2-1. ★ 승인 3케이스

| `resolution_case` | 상황 | 학부모에게 나갈 문구 |
| --- | --- | --- |
| `PARENT_IN_TIME` | 타임아웃 전 학부모 승인 | 정상 승인 |
| `STAFF_AFTER_TIMEOUT` | 무응답 후 타임아웃 경과, 담당선생님 승인 | "승인 시간이 지나 담임이 승인했습니다" |
| `STAFF_BEFORE_TIMEOUT` | 타임아웃 전인데 담당선생님이 먼저 승인 | "승인 시간이 남았지만 담임이 먼저 승인 처리했습니다" |

> 뒤 두 개를 같은 문구로 합치지 말 것. 숫자코드(1/2/3) 금지. 상태 전이는 **조건부 UPDATE**로 원자적 처리(낙관적 락 병용 금지).

---

## E'. domain/firewall — 와이파이 방화벽 해제

**승인 로직은 갖지 않는다** — `approval_request`에 위임.

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id / year | | §0-1 |
| enrollment_id | bigint, FK → student_enrollment | |
| approval_request_id | bigint, FK → approval_request | |
| requested_minutes | smallint CHECK (≤300) | |
| reason | text | |
| unlock_start_at / unlock_end_at | timestamptz | |
| zyxel_site_id | varchar(32) | ⚠️ 크레덴셜·제어 단위 미해결(E-1) |

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| firewall_violation | id, academy_id, enrollment_id(FK), firewall_request_id(FK, nullable), occurred_at, penalty_point_id(FK) | |
| firewall_restriction | id, academy_id, enrollment_id(FK), restricted_from, restricted_until, violation_count | ⚠️ 누적 기준 기간(연간/학기/영구) 미확인 |

---

## F. domain/meal — 급식 신청/취소 *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| meal_application | id, academy_id, **enrollment_id**(FK), apply_month, applied_at, status(APPLIED/CANCELLED), billing_id(FK → payment.billing) | *(수정: student_id→enrollment_id)*. 결제는 payment 도메인 공용 |
| meal_application_day | id, meal_application_id(FK), meal_date, meal_type CHECK IN ('L','D'), status(SCHEDULED/CANCELLED/SERVED) | 점심/저녁 구분, 하루 2끼 |
| meal_kiosk_check | id, academy_id, enrollment_id(FK), meal_date, checked_at, kiosk_device_id(FK) | *(수정: student_id→enrollment_id)* |

---

## G. domain/grade — 성적 조회 *(수정: 아래 표 기준으로 student_id/enrollment_id 재정리)*

| 테이블 | 핵심 컬럼 | 판단 |
| --- | --- | --- |
| mock_exam_score | id, academy_id, **student_id**(FK), exam_name, subject, score_type(B/D/P/PB/W), score, fetched_at | **사람 단위 유지(의도된 예외)** — 재수생의 연도 간 성적 추이를 연속으로 보고 싶을 가능성이 높음. 더프리미엄 API 자동조회 |
| internal_grade_record | id, academy_id, **enrollment_id**(FK), (컬럼 미정) | *(수정: student_id→enrollment_id)* — 내신은 그 학년(등록 건)에 종속. §4 블로커, 스텁 |
| self_grading_survey | id, academy_id, title, exam_name, open_at, close_at | |
| self_grading_response | id, survey_id(FK), **enrollment_id**(FK), subject, self_score, submitted_at | *(수정)* — 특정 시험 시점(그 해)에 종속 |
| grade_report_upload | id, academy_id, **enrollment_id**(FK), file_path, uploaded_at, uploaded_by | *(수정)* — 그 해 성적표 |

---

## H. domain/notice — 공지·질의응답·설문 *(판단: 사람 단위 유지)*

> 질문·설문 응답은 "그 해 등록건"에 종속시킬 필요가 크지 않다고 판단 — 학생이 재수하며 이어서 질문하는 맥락이 자연스러움. `student_id` 유지.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| notice | id, academy_id, title, content, target(ALL/STUDENT/PARENT), scope(GENERAL/CLASS), class_id(FK, nullable), author_id(employee_id), posted_at | |
| qna_post | id, academy_id, **student_id**(FK), title, content, is_anonymous, created_at | |
| qna_comment | id, qna_post_id(FK), mentor_id(employee_id), content, created_at | |
| survey | id, academy_id, title, survey_type(GRADE_INPUT/GENERAL), open_at, close_at | |
| survey_response | id, survey_id(FK), **student_id**(FK), response_json, submitted_at | |

---

## I. domain/chat — 착수 금지 (§4 블로커)

| 테이블(가안, 착수 금지) | 핵심 컬럼 |
| --- | --- |
| chat_message_mirror | id, sdk_channel_id, sdk_message_id, sender_type(STUDENT/PARENT/EMPLOYEE), sender_id, content, sent_at |

---

## J. daily-report *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| study_time_log | id, academy_id, **enrollment_id**(FK), log_date, study_minutes | *(수정: student_id→enrollment_id)* |
| daily_report | id, academy_id, **enrollment_id**(FK), report_date, study_minutes, ranking_branch, ranking_all, attendance_violation_summary_json, sent_at | *(수정)* |

---

## K. domain/notification — 알림 발송

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| notification_template | id, event_type, channel(KAKAO_ALIMTALK/FCM_PUSH), template_key, requires_student_name boolean DEFAULT true, content_confirmed boolean DEFAULT false | |
| notification_log | id, academy_id, template_id(FK), recipient_type(STUDENT/GUARDIAN), recipient_id, dedup_key UNIQUE, status(SENT/SKIPPED/FAILED), sent_at | |

---

## L. 특강/설명회 관리 *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| special_lecture | id, academy_id, name, capacity, start_date, end_date, price_billing_policy_id(FK → billing_policy) | |
| special_lecture_application | id, academy_id, special_lecture_id(FK), **enrollment_id**(FK), status(APPLIED/WAITLIST/CONFIRMED), applied_at | *(수정)* |
| special_lecture_attendance | id, academy_id, special_lecture_id(FK), **enrollment_id**(FK), session_date, status | *(수정)* |
| info_session_application | id, academy_id, name, phone, session_date, applied_at | 비회원 신청 가능성, 연락처 기반 |

---

## M. 기초 관리 *(수정: seat_assignment enrollment_id로 통합, year 컬럼명 통일)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| academy | id, acad_cd(UNIQUE), acad_nm, full_nm | `getDlabList` 3필드 대응 |
| department_master | id, academy_id, **year**, name | *(컬럼명 통일: reg_year→year)* |
| track_master | id, name(인문/자연/예체/공통) | academy_id 없음 |
| class_master | id, academy_id, **year**, name, class_type(FIXED/MOVING), homeroom_teacher_id(**FK → teacher**, N0 참고) | *(컬럼명 통일 + FK 대상 수정: employee→teacher)* |
| room_master | id, academy_id, name, capacity | |
| seat_master | id, academy_id, room_id(FK), seat_no, xpos, ypos | |
| seat_assignment | id, seat_id(FK → seat_master), **enrollment_id**(FK) | *(수정: student_id+reg_year→enrollment_id)* |
| period_master | id, academy_id, **year**, period_no, start_time, end_time | *(컬럼명 통일)*. attendance·학습계획 공유 마스터 |
| locker_master | id, academy_id, locker_no, assigned_enrollment_id(FK, nullable) | *(수정: 그 해 배정이므로 enrollment 참조)* |

---

## N. 선생님/직원/권한 관리 *(2026-08-03 확정)*

> **분리 사유**: 지점마다 **강사 조직과 행정 조직이 실제로 분리되어 운영된다**. 레거시도 `TB_TEACHER_MST`(PK `TCR_CD`) / 직원관리_TB(PK `EMP_CD`)로 나뉘어 있었고 합쳐 관리한 흔적이 없다 — 다만 이건 방증이지 근거가 아니다.
>
> **★ `teacher`에는 담당선생님(사감)만, 행정선생님은 `employee`에 들어간다.** 이 덕분에 `class_master.homeroom_teacher_id`와 `approval_request.escalation_teacher_id`의 **FK가 곧 "담당선생님 보장"**이 된다 — 배정할 때마다 역할을 검사할 필요가 없다. 합치면 이 보장이 사라지므로 합치지 말 것.
>
> **겸직은 없다** — 한 강사가 여러 지점을 맡거나 다른 지점에서 행정을 보는 경우가 없어, 배정 테이블 없이 `academy_id`를 직접 갖는다. *(이전 개정의 `teacher_academy_assignment`·`duty_type`은 폐기)*

### N0. teacher — 담당선생님(사감)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint, FK → academy | 겸직 없음 — 단일 지점 |
| name | varchar(20) | |
| phone / email | varchar | |
| hired_date / resigned_date | date | |
| created_at / updated_at / created_by / is_deleted | | §0-1 |

> 요구사항(F-4.10-2 "직원·강사 계정, 권한 관리, 지점별 접근 제어")에 필요한 만큼만 둔다. 레거시의 본명·사진·사번·직급·강사료 계좌(`REAL_NM`/`PHOTO`/`EMP_NO`/`LEVEL_NM`/`BANK_*`)는 **대응 요구사항이 없어 옮기지 않았다** — 레거시는 참고 사전이지 설계 기준이 아니다. 필요해지면 V2 이상에서 추가하면 된다.

### N1. employee — 행정 (행정선생님 포함)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint, FK → academy | |
| name / dept_name / position_name | varchar | |
| phone / email | varchar | |
| hired_date / resigned_date | date | |

> **학생 가입 승인(PENDING→ACTIVE)은 여기(행정)가 한다** — 담당선생님이 아니다.

### N2. role / account_role / permission — 권한

> RBAC는 **요구사항정의서가 정본**이다: **5단계** `SUPER_ADMIN` / `BRANCH_ADMIN` / `TEACHER`(담당선생님) / `STAFF`(행정선생님) / `READONLY`. V1에 seed로 넣었다.
>
> 담당/행정 구분을 `role`과 별도 컬럼에 **이중으로 두지 않는다** — 소속은 `teacher`/`employee` 테이블이, 권한은 `role`이 담당한다.

| 테이블 | 컬럼 | 비고 |
| --- | --- | --- |
| role | id, name | academy_id 없음 — 지점 무관 개념 |
| account_role | account_id(FK → account), role_id(FK → role) | 로그인 주체 기준으로 부여 — teacher/employee 어느 쪽인지 매번 분기하지 않기 위해 |
| permission | id, role_id(FK), resource, action, academy_scope(SINGLE/ALL) | 전 지점 조회는 상위 관리자만 |

---

## (참고) N 변경에 따라 함께 수정된 FK 목록

| 위치 | 이전 | 수정 |
| --- | --- | --- |
| M.class_master.homeroom_teacher_id | FK → employee | **FK → teacher** |
| E2.approval_request.escalation_target_employee_id | FK → employee | **escalation_target_teacher_id, FK → teacher** (에스컬레이션 대상은 항상 담당선생님이므로) |
| E2.resolver_id (resolver_type='TEACHER'일 때) | employee.id 참조 가정 | **teacher.id 참조** |
| H.notice.author_id | employee_id 단일 | **author_type CHECK IN ('TEACHER','EMPLOYEE') + author_id** 다형 참조 — **확정**: 전체공지는 행정선생님(employee), 반공지는 담당선생님(teacher)이라 작성자가 두 테이블에 걸친다 |
| H.qna_comment.mentor_id | employee_id | **teacher_id** (학업 멘토링은 선생님 역할로 판단) |
| P.counseling_log.counselor_id | employee_id | **teacher_id** (상담 주체는 선생님으로 판단) |
| G.grade_report_upload.uploaded_by, O.admission_result.entered_by, S.personal_record_submission.approved_by | employee_id | **변경 없음, employee_id 유지** — 교무 행정업무는 직원(employee) 소관으로 판단 |

> ⚠️ 위 표의 "판단"이라고 적은 것들은 확정 근거가 없어 제 추정입니다 — 특히 `notice`/`qna_comment`/`counseling_log`는 실제로 선생님(teacher)이 아니라 직원(employee)이 하는 업무일 수도 있어서, 틀렸으면 알려주시면 바로 되돌리겠습니다.

---

## O. 실적 관리 *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| admission_result | id, academy_id, **enrollment_id**(FK), university_name, department_name, result_type(합격/불합격/등록포기), entered_by(employee_id), entered_at | *(수정: student_id+reg_year→enrollment_id)* — 그 해 입시 결과이므로 |

---

## P. 상담일지 *(판단: 사람 단위 유지)*

> 입학예약 상담부터 연속 기록해야 한다는 요구사항 그대로 — `student_id` 유지.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| counseling_log | id, academy_id, **student_id**(FK), counselor_id(employee_id), counseling_type, counseling_date, content, rating(1~5), created_at | |

---

## Q. study_plan — 학습계획 *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| study_plan_item | id, academy_id, **enrollment_id**(FK), period_id(FK → period_master), plan_date, subject, content, fulfillment_status CHECK IN ('O','TRIANGLE','X') | *(수정: student_id→enrollment_id)* |
| study_plan_subitem | id, study_plan_item_id(FK), memo_text | |
| subject_fulfillment_rating | id, **enrollment_id**(FK), subject, reg_period, rating(1~5), rated_by(employee_id, nullable) | *(수정)* |

---

## R. daily_routine *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| daily_routine_log | id, academy_id, **enrollment_id**(FK), routine_date, item_name, is_completed | *(수정: student_id→enrollment_id)* |

---

## S. personal_record_card — 신상기록부 *(판단: 사람 단위 유지)*

> 신상 정보는 재등록해도 바뀌지 않는 게 자연스러움 — `student_id` 유지.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| personal_record_form_template | id, academy_id, grade CHECK IN ('HIGH2','HIGH3','N_SU'), form_type(1~4종), form_schema_json, version | I-17 확정 전 실제 필드 확정 불가 |
| personal_record_submission | id, **student_id**(FK), form_template_id(FK), response_json, status CHECK IN ('DRAFT','SUBMITTED','APPROVED','REJECTED'), submitted_at, approved_by(employee_id), approved_at, pdf_path | 관리자 승인 전 앱 비공개 |

---

## T. annual_event — 연간행사 마스터 *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| annual_event | id, academy_id, name, event_date, description | |
| annual_event_study_plan_link | annual_event_id(FK), study_plan_item_id(FK) | study_plan_item이 이미 enrollment 기준이라 자동으로 정합 |

---

## U. offline_qna / vocab_test — 대면 질의응답·영단어시험 *(수정: enrollment_id로 통합)*

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| offline_qna_session | id, academy_id, **enrollment_id**(FK), mentor_id(employee_id), session_date, notes | *(수정)* — 특정 시점 세션이므로 |
| vocab_test_result | id, academy_id, **enrollment_id**(FK), test_date, score | *(수정)* |

---

## V. domain/penalty — ★ 상벌점 + 규칙엔진

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| penalty_item | id, academy_id, year, item_name, point_value, category CHECK IN ('MERIT','DEMERIT') | 전년도 복사 대상 |
| penalty_rule | id, academy_id, year, trigger_type CHECK IN ('ATTENDANCE','DAILY_ROUTINE'), trigger_condition, penalty_item_id(FK) | ⚠️ 매핑 규칙 미확정(I-5) |
| penalty_point | id, academy_id, year, enrollment_id(FK), penalty_item_id(FK), points, reason, source CHECK IN ('KIOSK','ROUTINE','MANUAL'), occurred_at, idempotency_key UNIQUE, created_by | |

> **자동 부여는 멱등해야 한다** — `idempotency_key`(예: `ATTENDANCE:{enrollment_id}:{date}:{rule_id}`) 유니크 제약으로 DB에서 중복 부여를 막는다.
> I-5 미확정이라 수기 부여(`source='MANUAL'`)만 먼저 열고 규칙엔진은 인터페이스만.

---

## 전체 도메인 간 관계 요약 (수정본 — 실제 테이블 정의와 일치)

```
account (A0) ── student_id/guardian_id/employee_id (다형 FK, 정확히 하나만)

★ 학생은 2단이다. "그 해에 종속되는가"로 student_id/enrollment_id를 가른다 (§0-4).

student (사람, 영구)
 ├─ student_guardian_link → parent_guardian (A2,A3)
 ├─ personal_record_card (S)          ← 사람 단위
 ├─ counseling_log (P)                ← 사람 단위
 ├─ mock_exam_score (G)               ← 사람 단위(의도된 예외 — 연도 간 성적 추이)
 ├─ qna_post, survey_response (H)     ← 사람 단위
 └─ student_enrollment (등록 건, 기수별 1인 N행)
      ├─ class_assignment → class_master(homeroom_teacher_id) (A4, M)
      ├─ scholarship (A7)
      ├─ seat_assignment → seat_master (M)
      ├─ penalty_point → penalty_item ← penalty_rule (V)
      ├─ billing → payment_receipt / refund / virtual_account (B)
      ├─ attendance_tagging_log(7종) ─배치─> attendance_daily_status(ABSENT는 여기만) (C)
      ├─ absence_reason ─┐
      ├─ regular_schedule ┼─> approval_request → approval_item (E)
      ├─ firewall_request ┘        └─ firewall_violation → firewall_restriction (E')
      ├─ seat_leave_record → seat_master (C, M)
      ├─ meal_application(→billing) → meal_application_day (F)
      ├─ internal_grade_record(블로커), self_grading_response, grade_report_upload (G)
      ├─ study_plan_item → period_master (Q, M)
      ├─ daily_routine_log (R) ──> penalty_rule 트리거 (V)
      ├─ study_time_log, daily_report (J)
      ├─ special_lecture_application, special_lecture_attendance (L)
      ├─ offline_qna_session, vocab_test_result (U)
      └─ admission_result (O)

admission_reservation → admission_waitlist → student(전환) (A6)
account(A0) ── account_role ── role ── permission (N3)
teacher ── teacher_academy_assignment(academy_id, duty_type) (N0,N1) ← class_master.homeroom_teacher_id가 참조
employee (N2) — 강사 아닌 일반 직원, 단일 지점
notification_template ── notification_log (K)
annual_event (T) ──> study_plan_item 자동 반영 (Q)
```

**이 그림에서 읽어야 할 것**

1. 승인이 한 곳(E)으로 모인다
2. penalty(V)가 출결과 데일리루틴 양쪽에서 참조된다
3. **사람 단위는 5개뿐이다**: `mock_exam_score`, `qna_post`, `survey_response`, `counseling_log`, `personal_record_card`. 나머지는 전부 `enrollment_id`

---

## 대성전산 레거시 스키마 — 채택 vs 미채택

| 레거시 필드/패턴 | 채택 여부 | 이유 |
| --- | --- | --- |
| `RES_GB`(상품구분 10종) | 채택(단순화) | 실사용 4~5종만 CHECK 값으로 좁힘 |
| `BE_GB` 기본값 미정의 문제 | 반영 안 함 | CHECK 제약으로 원천 차단 |
| `MEMO`(자유텍스트 상태관리) | 반영 안 함 | `enrollment_status` 구조화 |
| `_BACKUP` 컬럼들 | 반영 안 함 | 레거시 마이그레이션 잔재 |
| `COVID_YN`/`TB_CHK`/`CD_CHK`(건강정보) | 반영 안 함 | 요구사항 없음, 민감정보 리스크 |
| PK 없는 테이블 | 반영 안 함 | 전부 `bigserial id` 기본 |
| 청구/수납/환불 3단 구조 | 채택 | 실무 검증된 구조 |
| 가상계좌 발급 개념 | 채택 | 실제 요구사항 |
| 대성전산 API(daesung) | **호출하지 않음 — 대체한다** | `integration/daesung`은 용도 소멸 |
| `TB_STUDENT_MST` / `TB_PDTL_RLS` 2단 구조 | **채택** | 학번·RFID를 등록 건에 붙이는 근거 |
| `RFID_NO` 비유니크 + 현재-플래그 | **채택 + 부분유니크 보강** | 이력 쌓임 + DB 레벨 중복 방지 추가 |
| `att_gn` 7종 코드(`S/T/A/D/N/C/R`) | **원본 그대로 채택** | 키오스크 응답값이 곧 저장값 |
| `RES_GB`에 `E:식대` 존재 | **근거로 채택** | 공용 결제 도메인의 방증 |

---

## §4 블로커 — 확정 전 관련 스키마/API 설계 금지

| 항목 | 상태 |
| --- | --- |
| **신상기록부 4종 양식** (I-17, 최우선) | 미정 — S 섹션 |
| 명단 구분 항목 (PRD 4.9, I-1) | 운영팀 재정의 필요. 유일하게 우회 불가 |
| 알림 템플릿 문구 (I-4) | 미정 — K. 알림톡 사전심사(E-5) 직렬 종속 |
| 상벌점 자동부여 규칙 매핑 (I-5) | 미정 — V.`penalty_rule` |
| 자리이탈 두 채널 충돌 규칙 (I-16) | 미정 — C. 2차 개발 이관 제안 상태 |
| 내신 성적 입력 양식 (I-11) | 미정 — G.`internal_grade_record` |
| 채팅 SDK 최종 선택 (E-6) | 미정 — I 섹션 |
| 결제 법적요건 | 미검토 — B 섹션 전체 |
| Zyxel 크레덴셜·제어 단위 (E-1) | 미정 — E'.`zyxel_site_id` |
| 민감정보 암호화 방식 | `birth_date`·`account_no` — 생년월일 조회조건 사용 여부부터 확인 |
| 방화벽 적발 누적 기준 기간 | 미확인 — E' |
| RBAC 축 정리 | 요구사항정의서 5단계 권한등급 vs CLAUDE.md 2종 직무구분 — 불일치 |
| 기존 재원생 데이터 이관 | 설계를 막는 블로커 아님, 컷오버 직전에만 필요 |
| **notice/qna_comment/counseling_log의 담당 주체(teacher vs employee)** (신규) | N 섹션 "참고" 표에 제 추정으로 표시해둠 — 실제 확인 필요 |

---

## 해소된 블로커 (참고)

- 키오스크 payload·RFID 매핑 (D-2, 최우선) → DSA 호환 API 제공으로 해소
- 방화벽 담당교사 매핑 → 반배정 자동연동 / 승인 타임아웃 → 10분 / 사전지정 UI → 불필요
- 급식 PG → 급식업체 기존 PG가 곧 대성 PG
- 상담 양식 → 목업 존재
- 학습계획 이행 표시(I-19) → ○/△/✗ 3단계, 교시 20분 편집은 반대로 확정
- 회원가입 연계 의혹 → 신상기록부와 무관, 중복방지 로직이었던 것으로 확인
- **선생님/직원 테이블 분리 여부** → 분리로 확정(다지점 겸직·지점별 담당 상이 이유). `teacher`+`teacher_academy_assignment`로 구현, N 섹션 참고. **지점(academy)·좌석(seat) 관련 테이블은 별도 담당자 작업 중이라 이번 수정에서 제외**