# D.Lab 전체 도메인 엔티티 설계

**정본 우선순위**: D.Lab 통합기획서(PRD) > CLAUDE.md(요약) > 이 문서(설계 초안). 이 문서는 CLAUDE.md 기준으로 재작성함.
**패키지**: `domain/{user, attendance, penalty, approval, schedule, payment, meal, grade, notice, firewall, chat, notification, ...}`
※ `approval`과 `penalty`는 각각 `firewall`·`attendance` 안에 두지 않는다 — 이유는 E·A5 참고.

---

## 0. 전 테이블 공통 규칙 (설계 시작 전 반드시 적용)

### 0-1. 공통 컬럼 — 예외 없이 모든 테이블

| 컬럼 | 타입 | 왜 필요한가 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id | bigint | **다지점.** 신규 테이블 만들 때 가장 자주 빠뜨린다 |
| year | smallint | **1년 단위 코호트**(독학재수)라 거의 모든 데이터가 연도에 종속. "전년도 복사"의 기준 |
| created_at / updated_at | timestamptz | |
| **created_by** | bigint (account_id) | **감사로그. 보안심사 직결 항목** |
| **is_deleted** | boolean DEFAULT false | soft delete |

> ⚠️ `created_by`·`is_deleted`를 나중에 붙이면 **그 이전 기간의 이력은 영영 복구할 수 없다.** 지금은 컬럼 추가지만 운영 데이터가 쌓인 뒤엔 손쓸 방법이 없다.
>
> 의도적 예외는 사유를 반드시 명시한다 — `parent_guardian`(지점 종속이 아니라 자녀를 따라감), `track_master`·`role`(지점 무관 고정 참조값).

### 0-2. 전년도 복사 (YearlySnapshotService)

```
department → course_type → class_group → curriculum → penalty_item → tuition
```

**이 순서를 지켜야 하고, 단순 `INSERT SELECT` 금지** — FK가 깨진다.

### 0-3. 개인정보

- 전화·주소·생년월일 포함 응답은 **상위 관리자만** 조회
- **엑셀 Export 마스킹 기본 ON** (`010-****-1234`)
- 발주처가 개인정보 유출 조사·벌금 사례로 민감도가 높다 — 임의 완화 금지

---

## A. domain/user — 회원관리

### A0. account — 로그인 계정

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| account_type | varchar(10) NOT NULL CHECK IN ('STUDENT','PARENT','EMPLOYEE') | JWT role claim 기준 |
| login_id | varchar(50) UNIQUE NOT NULL | 전화번호(학생·학부모) 또는 사번(직원) |
| password_hash | varchar(255), nullable |  |
| status | varchar(20) NOT NULL DEFAULT 'PENDING' CHECK IN ('PENDING','ACTIVE','SUSPENDED','WITHDRAWN') | 학생: PENDING→ACTIVE 이분법(중간상태 없음, 승인 전 앱 접근 완전 차단). 학부모: 즉시 ACTIVE |
| student_id / guardian_id / employee_id | bigint, FK, nullable (셋 중 정확히 하나만 NOT NULL) | `CHECK (num_nonnulls(...) = 1)` |
| last_login_at | timestamptz |  |
| created_at/updated_at | timestamptz |  |

> Refresh Token/Access Token 블랙리스트/로그인 rate limiting은 **Redis**에서 처리 (Postgres 테이블 아님).
>

### A1. ★ 학생 = "사람 + 등록 건" 2단 구조

> **한 테이블로 만들면 안 된다.** 레거시 실측(`TB_STUDENT_MST` / `TB_PDTL_RLS`)에서 확인된 구조이고, 이게 여러 문제를 한 번에 푼다:
>
> - **학번과 RFID 카드번호는 사람이 아니라 "등록 건"에 붙는다.** 그래서 학번 매년 초기화가 구조적으로 보장되고, 카드가 다음 기수에 재사용돼도 과거 기수 출결이 섞이지 않는다
> - 여기는 **독학재수라 1년 단위 코호트**다. 기수가 통째로 갈리므로 **연도를 넘는 학생 연속성을 가정하지 말 것**
> - 대신 **삼수로 재등록하면 같은 사람에 등록 행만 추가**되므로 동일인 추적이 공짜로 된다 (시안의 "상담 이력은 입학예약 상담부터 연속 기록"이 이걸로 성립)

### A1-1. student — 사람 (영구, 연도 무관)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | 내부 영구 식별자 |
| unique_code | varchar(20) UNIQUE NOT NULL | **학생 고유ID** — 마이페이지 상시 노출, 학부모 가입 시 이 값+전화번호로 검증 없이 즉시 연결(리스크는 클라이언트 인지·감수, 추가 검증 로직 만들지 않음). **사람에 붙으므로 재등록해도 안 바뀐다** |
| name | varchar(20) |  |
| phone | varchar(20) |  |
| birth_date | date | ⚠️ 암호화 여부는 미정 — 동명이인 구분 조회조건으로 쓰이면 인덱스를 못 탄다. **조회에 쓰는지 확인 후 결정** |
| gender | varchar(1) CHECK IN ('M','F') |  |
| school_name | varchar(64) | 출신고 — 사람 속성 |
| search_name_normalized | varchar(20), 인덱스 | 검색·정렬 대응 |
| created_at / updated_at / created_by / is_deleted | | §0-1 |

> `academy_id`·`year` 없음 — 지점과 연도는 등록 건의 속성이다(의도된 예외).

### A1-2. student_enrollment — 등록 건 (기수별, 1인 N행)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| student_id | bigint, FK → student | |
| academy_id | bigint | 지점. 기수마다 다를 수 있다 |
| year | smallint | 기수(등록년도) |
| student_no | varchar(20) | 학번 — **매년 초기화. PK/외부연동 키로 사용 금지.** `UNIQUE(academy_id, year, student_no)` |
| rfid_no | varchar(10), 인덱스 | 키오스크 카드 태깅 매칭 키. **DSA 호환 엔드포인트 25개 중 13개가 이 값을 조회키로 사용 — 최우선 반영** |
| is_current | boolean DEFAULT true | **현재 유효한 등록 건 플래그** (레거시 `STAT_GB='T'` 대응) |
| grade | varchar(10) CHECK IN ('HIGH2','HIGH3','N_SU') | 고2/고3/N수생 3분류 (PRD "성인" = N수생) |
| track | varchar(10) CHECK IN ('HUMANITIES','SCIENCE','ART','COMMON') |  |
| enrollment_status | varchar(20) NOT NULL DEFAULT 'ENROLLED' CHECK IN ('ENROLLED','ON_LEAVE','WITHDRAWN','GRADUATED') | 재원 상태 — `account.status`(가입승인 상태)와 **별개** |
| admission_date / withdrawal_date | date |  |
| created_at / updated_at / created_by / is_deleted | | §0-1 |

> **⚠️ `rfid_no`는 UNIQUE가 아니다.** 등록 건마다 쌓이는 **이력**이기 때문이다. 그래서 카드번호로 학생을 찾을 때는 **반드시 `is_current = true`로 걸러야 한다.** 이걸 빠뜨리면 **퇴원생 카드로 태깅이 통과한다.** 키오스크 조회 쿼리 전부에 해당된다.

> 이후 이 문서에서 `student_id(FK)`라고 적힌 것은, **연도에 종속되는 데이터라면 실제로는 `enrollment_id`를 참조해야 한다** (출결·상벌점·반배정·청구 등 대부분이 여기 해당). 사람 단위로 이어져야 하는 것(상담 이력, 신상기록부)만 `student_id`를 쓴다.

### A2. parent_guardian — 학부모

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| name | varchar(20) |  |
| phone | varchar(20) UNIQUE |  |
| gender | varchar(1) CHECK IN ('F','M') | `getParentHpList`의 `p_gb` 대응 — 부/모 구분 |
| created_at | timestamptz |  |

> academy_id 없음 — 학부모는 지점 종속이 아니라 자녀를 따라감(의도된 예외).
>

### A3. student_guardian_link — 다자녀 연결

계정 1개 + 자녀 여러 명 연결 구조로 확정(완전 분리 계정 기각 — 사용성·알림로직 둘 다 복잡해짐).

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| student_id | bigint, FK → student |  |
| guardian_id | bigint, FK → parent_guardian |  |
| relation_order | smallint |  |
| PRIMARY KEY (student_id, guardian_id) |  |  |

### A4. class_assignment — 반 배정 + 담임 자동 연동

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| academy_id | bigint |  |
| student_id | bigint, FK → student |  |
| class_id | bigint, FK → class_master | 담임(담당선생님)은 `class_master.homeroom_teacher_id`로 자동 결정 — 학생별 승인자 사전지정 UI 불필요 |
| reg_year | smallint |  |
| class_type | varchar(10) CHECK IN ('FIXED','MOVING') |  |
| assigned_at | timestamptz |  |
| is_active | boolean DEFAULT true |  |

### A5. (이동) 상벌점 → `domain/penalty` 로 분리

**`attendance`나 `user` 하위에 두지 말 것.** 상벌점 규칙엔진은 **출결과 데일리루틴 양쪽에서 트리거**된다(요구사항정의서 I-5). `attendance` 하위에 두면 `daily_routine → attendance` 순환 참조가 난다. → **V 섹션 참고.**

### A6. admission_waitlist / admission_reservation — 대기자·입학예약

| 테이블 | 컬럼 | 비고 |
| --- | --- | --- |
| admission_reservation | id, academy_id, name, student_phone, parent_phone, birth_date(암호화), desired_admission_date, consultation_note, created_at |  |
| admission_waitlist | id, reservation_id(FK), waitlist_no, status(WAITING/NOTIFIED/CONVERTED), notified_at, converted_student_id(FK, nullable) |  |

### A7. scholarship — 장학생

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| student_id | bigint, FK → student |  |
| reg_year | smallint |  |
| scholarship_type | varchar(20) |  |
| discount_rate | numeric(5,2) |  |

---

## B. domain/payment — 수납+급식 결제 공용 (D.Lab 자체 PG사 = "대성 PG")

> **확정**: 대성전산 수납 프로그램 사용 안 함. 수납·급식 결제 전부 이 공용 도메인에서 처리. PG사는 "대성 PG"(대성전산과 무관한 별도 계열사) — 급식업체가 원래 쓰던 PG도 동일 대성 PG로 확인됨. 전자금융거래법 등 세부 법적요건은 §4 블로커, 스키마 확정 전 선행 필요.
>

### B1. billing_policy — 청구 기준 (상품 마스터)

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| academy_id | bigint |  |
| product_type | varchar(20) CHECK IN ('TUITION','SPECIAL_LECTURE','DORM','MEAL','BOOK','OTHER') | `MEAL` 포함 — meal 도메인도 이 공용 정책 사용 |
| name | varchar(64) |  |
| amount | numeric(12,0) |  |
| tax_applicable | boolean |  |
| valid_from/valid_to | date |  |
| is_active | boolean |  |

### B2. billing — 청구 발행

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| academy_id | bigint |  |
| student_id | bigint, FK → student |  |
| billing_policy_id | bigint, FK → billing_policy |  |
| reg_year | smallint |  |
| total_amount / discount_amount / target_amount / received_amount | numeric(12,0) |  |
| status | varchar(20) CHECK IN ('UNPAID','PARTIAL','PAID','CANCELLED') | 미납자 명단은 이 컬럼 기준 쿼리 |
| created_at | timestamptz |  |

### B3. payment_receipt — 수납 내역

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| academy_id | bigint |  |
| billing_id | bigint, FK → billing |  |
| payment_method | varchar(20) CHECK IN ('CARD','VIRTUAL_ACCOUNT','CASH') |  |
| amount | numeric(12,0) |  |
| pg_transaction_id | varchar(64), nullable | 대성 PG 거래ID |
| paid_at | timestamptz |  |

### B4. virtual_account — 가상계좌

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| academy_id | bigint |  |
| billing_id | bigint, FK → billing |  |
| bank_name | varchar(64) |  |
| account_no | varchar(64), **암호화 대상** |  |
| valid_from/valid_to | date |  |
| is_used | boolean |  |

### B5. refund — 환불/취소

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK |  |
| academy_id | bigint |  |
| billing_id | bigint, FK → billing |  |
| payment_receipt_id | bigint, FK → payment_receipt |  |
| refund_method | varchar(20) CHECK IN ('CARD_CANCEL','ACCOUNT_TRANSFER','CASH') |  |
| refund_amount | numeric(12,0) |  |
| bank_name/account_no | varchar, **암호화 대상** |  |
| approved_at | timestamptz |  |
| created_by | varchar(32) |  |

---

## C. domain/attendance — 출결·벌점·자리이탈

> **자리이탈은 QR 순찰 방식이 아님**: PRD 3.6 "사감 QR 순찰"은 채택 안 함. 대신 **자리이탈 여부**만 추적. 트리거 경로는 **키오스크 카드 태깅 + 앱 신청(패드 소지 시, 요구사항정의서 F-4.11-8)** 양쪽 모두 가능 — 두 경로가 겹칠 때 처리 규칙은 §4 블로커.
>
>
> **태깅 원장(log) vs 일자 집계(status) 2단 분리**: `ABSENT`(결석)는 실제 태깅 이벤트가 아니라 배치가 "그 날 아무 태깅도 없었다"를 보고 확정하는 **파생 상태값**이라 원장에 넣으면 안 됨. 원장은 실제 발생한 이벤트만, 집계는 하루 단위 최종 상태만 담당.
>

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| kiosk_device | id, academy_id, location, device_type(ATTENDANCE/MEAL) |  |
| attendance_tagging_log | id, academy_id, enrollment_id(FK), event_type(7종, 아래 표), source(KIOSK_NFC/APP_QR), kiosk_device_id(FK, nullable), period_id(FK → period_master, nullable), recorded_at | 실제로 발생한 태깅만. `ABSENT` 없음(아래 참고) |
| attendance_daily_status | id, academy_id, student_id(FK), attendance_date, final_status CHECK IN ('PRESENT','LATE','ABSENT','EARLY_LEAVE'), calculated_at | 배치가 `attendance_tagging_log`+`period_master`(등원시각 기준) 대조해서 확정하는 **파생 일자 상태**. `ABSENT`는 오직 여기에만 존재 |
| absence_reason | id, academy_id, enrollment_id(FK), attendance_date, reason_type(ABSENCE/LATE/EARLY_LEAVE/OUTING), reason_text, submitted_at, deadline_at(당일 자정), **approval_request_id**(FK → approval_request) | 승인 주체는 **관리자**(§5-1: "사유 승인/수정"은 관리자 웹 담당, 학부모 아님). 승인 상태는 자체 컬럼이 아니라 `approval_request`(E2)에서 읽는다. ⚠️ "벌점 확정 후 사유 승인 불가"의 확정 기준 미정(I-10) |
| seat_leave_record | id, academy_id, student_id(FK), seat_id(FK → seat_master, nullable), trigger_source(KIOSK_CARD/APP_REQUEST), status(LEFT/RETURNED), left_at, returned_at, kiosk_device_id(FK, nullable) | "좌석이탈 신청"과 동일 개념(F-4.11-8) — `trigger_source='APP_REQUEST'`가 신청에 해당. ⚠️ 두 트리거 경로 충돌 규칙 미정(§4 블로커) **+ 0723 회의에서 개발팀이 실시간 좌석표 UI를 공수·리스크 이유로 2차 개발 이관을 제안한 상태** — 클라이언트 확정 전까지 Phase/스코프 자체가 불확실. 키오스크 스펙(D-2)에도 종속. 확정 전 API 설계 금지 |
| (미등원 알림) | 별도 테이블 아님 | 등원시간(지점 공통) 정각에 스케줄러 실행 → `attendance_tagging_log`에 등원 없는 학생 필터링(사전 결석사유 제출자 제외) → 카카오 알림톡. 결과는 `notification_log`(K)에 기록 |

### C-1. ★ 출결 이벤트 7종 — DSA 원본 코드를 그대로 저장한다

키오스크는 DSA와 동일한 응답을 기대하므로 **저장 값이 곧 응답 값**이다. 우리 식으로 예쁘게 바꾸면 호환 구획에서 매번 역매핑해야 하고, 하나만 틀려도 키오스크 파싱이 깨진다.

| `att_gn` | 의미 | 비고 |
| --- | --- | --- |
| `S` | 등원 | |
| `T` | 하원 | **순공시간 계산의 필수 짝** — 이게 없으면 공부시간을 못 낸다 |
| `A` | 지각 | |
| `D` | 외출 | 일반 외출 |
| `N` | 사유 외출 | 사유 제출된 외출 — `D`와 구분해야 벌점 판정이 갈린다 |
| `C` | 조퇴 | |
| `R` | 복귀 | 외출/조퇴 후 돌아옴 |

> ⚠️ **요구사항정의서 2시트의 5종(`ON_TIME/LATE/ABSENT/OUT/EXCUSED`)은 틀렸다.** 하원(`T`)과 복귀(`R`)가 빠져 있고, 하원 없이는 순공시간 계산이 불가능하다.
>
> ⚠️ **`ABSENT`(결석)를 이 enum에 넣지 말 것.** 결석은 "안 찍은 것"이라 태깅 로그에 남을 수 없다. 배치가 일자 단위로 확정하는 **파생 상태값**이고, 오직 `attendance_daily_status`에만 존재한다. 둘을 같은 enum에 섞는 순간 "결석 이벤트를 INSERT"하는 코드가 생긴다.

---

## D. domain/schedule — 정기일정

승인은 **학부모 앱에서만** 처리 (관리자 웹 관여 없음 — 에스컬레이션 모델 아님, firewall과 다름).

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| regular_schedule | id, academy_id, student_id(FK), apply_month, schedule_type(현강/과외), day_of_week, start_time, end_time, location_note, registered_at | 매월 1일 등록 |
| ~~schedule_approval~~ | **별도 테이블 두지 말 것** | `approval_request`(E2)에 위임. `approval_item`에서 `approver_type='PARENT'` + `escalation_approver_type=NULL`로 두면 "학부모만, 에스컬레이션 없음"이 그대로 표현된다 |
| schedule_variance_log | id, regular_schedule_id(FK), actual_check_in_at, variance_minutes, is_recognized | 30분차 판정 |

---

## E. domain/approval — ★ 공통 승인 라우팅

> **`firewall` 안에 두지 말 것.** 요구사항정의서 F-4.11-5는 **사유신청·정기일정·방화벽을 하나의 라우팅 엔진**에 태운다. `firewall` 안에 두면 사유신청에서 같은 로직을 또 짜게 된다.

### E1. approval_item — 승인 항목 마스터

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id / year | | §0-1 |
| request_type | varchar(30) CHECK IN ('FIREWALL_UNLOCK','ABSENCE_REASON','REGULAR_SCHEDULE') | 확장 시 여기 추가 |
| approver_type | varchar(10) CHECK IN ('PARENT','TEACHER','AUTO') | 1차 승인 주체 |
| timeout_minutes | smallint, nullable | NULL이면 에스컬레이션 없음 |
| escalation_approver_type | varchar(10), nullable | 타임아웃 후 승인 주체. NULL이면 에스컬레이션 없음 |

> **항목마다 정책이 다르다**: 방화벽은 `PARENT` → 10분 → `TEACHER`. 정기일정은 `PARENT`만(에스컬레이션 없음). 사유신청은 `TEACHER`(관리자 승인).

### E2. approval_request — 승인 요청 건

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id / year | | §0-1 |
| approval_item_id | bigint, FK → approval_item | |
| enrollment_id | bigint, FK → student_enrollment | 신청 학생 |
| status | varchar(20) CHECK IN ('PENDING','APPROVED','REJECTED','CANCELED') | **상태는 이 3+1종뿐.** 아래 `resolution_case`와 층위가 다르다 |
| requested_at | timestamptz | |
| **timeout_minutes** | smallint NOT NULL | 신청 시점 스냅샷. 정책이 바뀌어도 처리된 건은 흔들리면 안 된다 |
| **escalation_at** | timestamptz NOT NULL | `requested_at + timeout_minutes`. 케이스 판별 기준 |
| **escalation_target_employee_id** | bigint, FK → employee, nullable | 신청 시점 담당선생님 **스냅샷**. `class_assignment → class_master.homeroom_teacher_id`로 자동 도출 — **승인자 사전지정 UI 불필요** |
| resolved_at | timestamptz, nullable | |
| resolver_type | varchar(10) CHECK IN ('PARENT','TEACHER'), nullable | |
| resolver_id | bigint, nullable | `resolver_type`에 따라 guardian_id 또는 employee_id |
| **resolution_case** | varchar(30) CHECK IN ('PARENT_IN_TIME','STAFF_AFTER_TIMEOUT','STAFF_BEFORE_TIMEOUT'), nullable | 아래 참고 |
| reject_reason | varchar(500), nullable | |

### E2-1. ★ 승인 3케이스 — 단순 레이스가 아니다

신청 시 학부모와 담당선생님에게 **동시 발송**되지만, 승인 시점에 따라 셋으로 갈린다. 판별은 **승인시각 vs `escalation_at`** 비교다.

| `resolution_case` | 상황 | 학부모에게 나갈 문구 |
| --- | --- | --- |
| `PARENT_IN_TIME` | 타임아웃 전 학부모 승인 | 정상 승인 |
| `STAFF_AFTER_TIMEOUT` | 무응답으로 타임아웃 경과 후 담당선생님 승인 | "승인 시간이 지나 담임이 승인했습니다" |
| `STAFF_BEFORE_TIMEOUT` | 타임아웃 전인데 담당선생님이 먼저 승인 | "승인 시간이 남았지만 담임이 먼저 승인 처리했습니다" |

> **뒤 두 개를 같은 문구로 합치지 말 것.** 학부모 입장에서 전혀 다른 상황이고, 합치면 "왜 시간 남았는데 담임이 승인했지" 하는 혼란이 생긴다.

> **숫자코드(1/2/3) 금지** — 레거시 `BE_GB` 같은 해독 불가 코드의 재발이다.

> **상태 전이는 원자적으로.** 학부모와 담당선생님 승인이 같은 순간 들어올 수 있다. 조회 후 저장이 아니라 **조건부 UPDATE**(`WHERE status='PENDING'`, 갱신행 0이면 이미 처리됨)로 처리한다. 낙관적 락(`version` 컬럼)은 쓰지 않는다 — 두 방식을 섞으면 어느 쪽이 실제로 동시성을 막는지 불분명해진다.

> `status`에 `TIMEOUT_ESCALATED` 같은 값을 넣지 말 것 — "타임아웃 후 승인됐다"는 결과 속성이라 `resolution_case`가 담당한다. 섞으면 "에스컬레이션됐지만 거절"을 표현할 수 없다.

---

## E'. domain/firewall — 와이파이 방화벽 해제

**승인 로직은 갖지 않는다** — `approval_request`에 위임하고, 여기는 해제 자체의 고유 정보만 담는다.

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| id | bigserial, PK | |
| academy_id / year | | §0-1 |
| enrollment_id | bigint, FK → student_enrollment | |
| **approval_request_id** | bigint, FK → approval_request | 승인 상태는 전부 여기서 읽는다 |
| requested_minutes | smallint CHECK (≤300) | 최대 5시간 |
| reason | text | |
| unlock_start_at / unlock_end_at | timestamptz | 실제 해제 구간 |
| zyxel_site_id | varchar(32) | Nebula API 호출 대상 (⚠️ 크레덴셜·제어 단위는 E-1 미해결) |

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| firewall_violation | id, academy_id, enrollment_id(FK), firewall_request_id(FK, nullable), occurred_at, penalty_point_id(FK) | 적발 이력 |
| firewall_restriction | id, academy_id, enrollment_id(FK), restricted_from, restricted_until, violation_count | 2회 적발 시 2주 제한. ⚠️ **적발 횟수의 누적 기준 기간(연간/학기/영구) 미확인** |

---

## F. domain/meal — 급식 신청/취소 (결제는 payment 도메인에 위임)

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| meal_application | id, academy_id, student_id(FK), apply_month, applied_at, status(APPLIED/CANCELLED), **billing_id**(FK → payment.billing) | 월말 일괄 신청. 결제는 `billing_policy(product_type='MEAL')` → `billing` → `payment_receipt`로 처리 — **별도 meal_payment 테이블 없음**(수납·급식 공용 결제 원칙 확정, CLAUDE.md §3) |
| meal_application_day | id, meal_application_id(FK), meal_date, **meal_type CHECK IN ('L','D')**, status(SCHEDULED/CANCELLED/SERVED) | 달력 UI 단위, 주말 제외. **점심/저녁 구분 추가** — 하루 2끼 가능, 레거시·API도 L/D 사용 |
| meal_kiosk_check | id, academy_id, student_id(FK), meal_date, checked_at, kiosk_device_id(FK) | 신청여부 대조 |

---

## G. domain/grade — 성적 조회

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| mock_exam_score | id, academy_id, student_id(FK), exam_name, subject, score_type(B/D/P/PB/W), score, fetched_at | 더프리미엄 API 자동조회 — 회원가입 시점에도 자동조회되어 별도 학생 입력 불필요 |
| internal_grade_record | id, academy_id, student_id(FK), (컬럼 미정) | ⚠️ **내신 성적 — 입력 양식 자체가 §4 블로커**(학년별 항목, 등급/원점수 여부, 학기단위 등 미정). 확정 전 스키마 확정 금지, 스텁으로만 존재 |
| self_grading_survey | id, academy_id, title, exam_name, open_at, close_at | 가채점 설문 |
| self_grading_response | id, survey_id(FK), student_id(FK), subject, self_score, submitted_at |  |
| grade_report_upload | id, academy_id, student_id(FK), file_path, uploaded_at, uploaded_by | 성적표 PDF |

---

## H. domain/notice — 공지·질의응답·설문

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| notice | id, academy_id, title, content, target(ALL/STUDENT/PARENT), scope(GENERAL/CLASS), class_id(FK, nullable), author_id(employee_id), posted_at | **작성 권한 분리**: 전체공지(scope=GENERAL)는 행정선생님만, 반공지(scope=CLASS)는 담당선생님만 작성 가능 — 조회는 역할 무관 전체 공유 |
| qna_post | id, academy_id, student_id(FK), title, content, is_anonymous, created_at |  |
| qna_comment | id, qna_post_id(FK), mentor_id(employee_id), content, created_at |  |
| survey | id, academy_id, title, survey_type(GRADE_INPUT/GENERAL), open_at, close_at |  |
| survey_response | id, survey_id(FK), student_id(FK), response_json, submitted_at |  |

---

## I. domain/chat — 착수 금지 (§4 블로커)

> **SDK 사용 자체는 확정**(자체구현 폐기). 하지만 **어떤 SDK인지는 미정** — 여러 후보(Sendbird 등)를 팀이 프로토타입으로 만들어 클라이언트가 최종 선택 예정. `integration/sendbird` 패키지명도 확정 벤더가 아니라 플레이스홀더. SDK 확정 전까지 스키마·실제 구현 착수 금지. SDK로 가더라도 "학원 소유 DB" 원칙상 메시지 원본은 Webhook으로 자체 DB에 동기화해야 함.
>

| 테이블(가안, 착수 금지) | 핵심 컬럼 |
| --- | --- |
| chat_message_mirror | id, sdk_channel_id, sdk_message_id, sender_type(STUDENT/PARENT/EMPLOYEE), sender_id, content, sent_at |

---

## J. daily-report — PRD 3.10

QR 순찰 결과 항목은 제외(C 섹션 참고 — 자리이탈로 대체). 자리이탈 요약을 포함할지는 추후 결정.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| study_time_log | id, academy_id, student_id(FK), log_date, study_minutes | 출결기록 기반 파생 집계 |
| daily_report | id, academy_id, student_id(FK), report_date, study_minutes, ranking_branch, ranking_all, attendance_violation_summary_json, sent_at |  |

---

## K. domain/notification — 알림 발송

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| notification_template | id, event_type, channel(KAKAO_ALIMTALK/FCM_PUSH), template_key, requires_student_name boolean DEFAULT true, content_confirmed boolean DEFAULT false | **모든 템플릿에 학생명 변수 필수** — 다자녀 학부모 혼동 방지. 문구 미확정 시 `content_confirmed=false`로 발송 자체를 보류 |
| notification_log | id, academy_id, template_id(FK), recipient_type(STUDENT/GUARDIAN), recipient_id, dedup_key UNIQUE, status(SENT/SKIPPED/FAILED), sent_at | `dedup_key`로 중복발송 방지. `content_confirmed=false`인 템플릿은 `status='SKIPPED'`로 기록만 하고 실제 미발송 |

> ⚠️ 알림 템플릿 **문구** 자체는 §4 블로커 — 카카오 알림톡은 사전심사 필요해 조기 확정 유리.
>

---

## L. 특강/설명회 관리

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| special_lecture | id, academy_id, name, capacity, start_date, end_date, price_billing_policy_id(FK → billing_policy) |  |
| special_lecture_application | id, academy_id, special_lecture_id(FK), student_id(FK), status(APPLIED/WAITLIST/CONFIRMED), applied_at |  |
| special_lecture_attendance | id, academy_id, special_lecture_id(FK), student_id(FK), session_date, status |  |
| info_session_application | id, academy_id, name, phone, session_date, applied_at | 비회원 신청 가능성 있어 연락처 기반 |

---

## M. 기초 관리 (지점·학과·반·강의실·좌석·교시·사물함)

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| academy | id, acad_cd(UNIQUE), acad_nm, full_nm | **신규** — 전 테이블이 참조하는 `academy_id`의 정의 테이블이 빠져있었음. `getDlabList`가 내려주는 acad_cd/acad_nm/full_nm 3필드 대응 |
| department_master | id, academy_id, reg_year, name | 전년도 복사 대상 |
| track_master | id, name(인문/자연/예체/공통) | academy_id 없음 — 지점 무관 고정 참조값 |
| class_master | id, academy_id, reg_year, name, class_type(FIXED/MOVING), homeroom_teacher_id(FK → employee) | firewall 에스컬레이션·A4의 근거 마스터 |
| room_master | id, academy_id, name, capacity |  |
| seat_master | id, academy_id, room_id(FK), seat_no, xpos, ypos | **신규** — 좌석 배치도 렌더링용 좌표 포함. 키오스크 쪽 좌석 테이블과 동일 개념이면 그대로 이관 검토 |
| seat_assignment | id, seat_id(FK → seat_master), student_id(FK), reg_year, assigned_at | 학생별 좌석 배정(연도 단위) |
| period_master | id, academy_id, reg_year, period_no, start_time, end_time | **신규** — 등원/지각 자동판별(attendance_daily_status)과 **학습계획 도메인이 공유하는 단일 마스터**. 두 번 만들지 말 것 |
| locker_master | id, academy_id, locker_no, assigned_student_id(FK, nullable) |  |

---

## N. 직원/권한 관리

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| employee | id, academy_id, name, dept_name, position_name, phone, email, hired_date, resigned_date |  |
| role | id, name(담당선생님/행정선생님/관리자 등) | academy_id 없음 — role 자체는 지점 무관 개념 |
| employee_role | employee_id(FK), role_id(FK) |  |
| permission | id, role_id(FK), resource, action, academy_scope(SINGLE/ALL) | 지점별 접근 제어 — 상위 관리자만 전 지점 조회 가능 |

---

## O. 실적 관리

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| admission_result | id, academy_id, student_id(FK), reg_year, university_name, department_name, result_type(합격/불합격/등록포기), entered_by(employee_id), entered_at |  |

---

## P. 상담일지 (입시/학습 상담, CS상담과 별개)

> 목업 존재 확인, 이를 기준으로 진행 가능(§4 블로커 사실상 해소). 상담 종류가 여러 개 — 도메인 패키지 구조는 목업 검토 후 별도 확정. CS상담(민원성)은 기존대로 구글시트 유지, 변경 없음(이 스키마 범위 아님). **담임별 상담현황(반/담임 필터)은 Phase3.**
>

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| counseling_log | id, academy_id, student_id(FK), counselor_id(employee_id), counseling_type, counseling_date, content, rating(1~5), created_at | 목업 기준 상세 컬럼 재검토 필요. `rating`은 0723 회의 "과목종합 상담 = 별점" 요구 반영(가안) |

---

## V. domain/penalty — ★ 상벌점 + 규칙엔진

> **`attendance` 하위에 두지 말 것.** 규칙엔진은 **출결과 데일리루틴 양쪽에서 트리거**된다(요구사항정의서 I-5). `attendance` 아래 두면 `daily_routine → attendance` 순환 참조가 난다. 그래서 둘 다에서 참조 가능한 독립 도메인으로 둔다.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| penalty_item | id, academy_id, year, item_name, point_value, category CHECK IN ('MERIT','DEMERIT') | 항목 마스터. **전년도 복사 대상**(§0-2 순서에 포함) |
| penalty_rule | id, academy_id, year, trigger_type CHECK IN ('ATTENDANCE','DAILY_ROUTINE'), trigger_condition, penalty_item_id(FK) | 트리거 → 항목 매핑. ⚠️ **매핑 규칙 자체가 미확정(I-5)** — 스키마만 두고 규칙은 비워둔다 |
| penalty_point | id, academy_id, year, enrollment_id(FK), penalty_item_id(FK), points, reason, source CHECK IN ('KIOSK','ROUTINE','MANUAL'), occurred_at, **idempotency_key** UNIQUE, created_by | 실제 부여 이력 |

> **⚠️ 자동 부여는 멱등해야 한다.** 출결·루틴 이벤트가 중복 트리거되면 **점수가 두 번 부여된다**(실행가이드 3.3이 명시한 리스크). 키오스크 재태깅·배치 재실행·다중 인스턴스 전부 현실적인 경로다. `idempotency_key`(예: `ATTENDANCE:{enrollment_id}:{date}:{rule_id}`)에 유니크 제약을 걸어 **DB에서 막는다.** 애플리케이션 레벨 체크만으로는 동시 실행을 못 막는다.

> I-5(규칙 매핑)가 미확정이라 **수기 부여(`source='MANUAL'`)만 먼저 열고**, 규칙엔진은 인터페이스만 잡아둔다. 실행가이드도 같은 우회를 권한다.

---

## Q. study_plan — 학습계획 (Phase 3, 0723 회의 결정 반영)

> **이행 표시 방식 확정(0723)**: 항목별(일일) 이행은 **○/△/✗ 3단계**(드래그/% 방식 기각 — 모바일 오조작·자기평가 신뢰도 문제), 과목 종합(담임 상담)은 **별점** 또는 항목 자동집계 %.
**교시 20분 단위 학생 편집은 반대로 확정** — 교시 그리드는 관리자(웹)만 편집, 학생 앱은 서버가 준 그리드를 렌더만 함. 대신 교시 셀 내부에 "세부메모/서브아이템" 입력은 허용(그리드 골격 불변). 반마다 교시 시간이 달라지는 것도 지양(운영 혼란) — `period_master`가 academy+reg_year 단위로만 존재하는 현재 설계가 이 요구와 부합.
>

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| study_plan_item | id, academy_id, student_id(FK), period_id(FK → period_master), plan_date, subject, content, fulfillment_status CHECK IN ('O','TRIANGLE','X') | 교시 그리드 골격은 불변, 이 레코드가 셀 안의 실제 계획/이행 데이터 |
| study_plan_subitem | id, study_plan_item_id(FK), memo_text | 교시 셀 내부 세부메모 — 학생이 임의로 교시 자체를 쪼개는 대신 이걸로 흡수 |
| subject_fulfillment_rating | id, student_id(FK), subject, reg_period(주/월 등), rating(1~5), rated_by(employee_id, nullable) | 과목 종합 — 담임 부여 별점 또는 자동집계, 방식은 운영팀 확정 전까지 병행 지원 |

## R. daily_routine — 데일리루틴 (0723: 교시 탭과 함께 캘린더 형식 편집)

> 0723 메모: "교시시간 탭, 데일리루틴 설정도 시간 편집, 캘린더 형식으로" — Q의 `period_master`/교시 그리드 편집 UI와 동일한 캘린더 형식 편집 UI를 공유할 가능성. 상세 요구사항은 여전히 확인 필요.
>

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| daily_routine_log | id, academy_id, student_id(FK), routine_date, item_name, is_completed | ⚠️ 가안 — Daily Report(J)와의 관계, 편집 UI 구체 사양 확인 필요 |

## S. personal_record_card — 신상기록부 (F-4.11-9, I-17 — Phase1 최우선 블로커)

> **0728 회의로 실체 확인됨** :
>
> - **4종 양식**, **학년별 폼 분기** — 어떤 학년이 어떤 양식인지는 여전히 I-17로 운영팀 확정 대기, **Phase1(회원가입 온보딩 편입) 착수 전 필수 블로커**
> - **설문형 입력** (동적 폼) + 완료 후 **PDF 생성**
> - 웹에서 설문폼 **입력·수정** 가능, PDF **열람** 가능
> - **관리자 승인 후에만 학생 앱에서 열람 가능** (승인 전엔 비공개)
> - 열람/PDF 기능 자체는 **Phase 3**, 폼 입력·수집은 **Phase 1**(회원가입 온보딩에 편입)
> - ⚠️ **회원가입과의 연계 방식이 회의록상 문장이 불명확함**: "회원가입 못하게. 휴대폰인증하면 그걸로 비교해서. 학부모 코드 입력하면 가입할 수 있게" — 신상기록부 제출 여부가 학생 회원가입 승인 조건에 포함되는 건지, 아니면 이미 A0에 있는 기존 인증 흐름(학생 휴대폰인증, 학부모 학생고유ID)을 그대로 설명한 것뿐인지 문장만으로는 판단 불가. **이 부분은 원문 그대로 재확인 필요** — 추측으로 스키마에 반영하지 않음.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| personal_record_form_template | id, academy_id, grade CHECK IN ('HIGH2','HIGH3','N_SU'), form_type(1~4종), form_schema_json, version | 학년별 4종 양식. **I-17 확정 전 실제 필드 확정 불가** — schema_json 구조로 유연하게 대응 |
| personal_record_submission | id, student_id(FK), form_template_id(FK), response_json, status CHECK IN ('DRAFT','SUBMITTED','APPROVED','REJECTED'), submitted_at, approved_by(employee_id), approved_at, pdf_path | 관리자 승인 전엔 앱 비공개(`status != 'APPROVED'`이면 앱에서 조회 차단) |

## T. annual_event — 연간행사 마스터 (F-4.11-10, Phase 3)

관리자 입력 → 학생 학습계획(Q)에 반영.

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| annual_event | id, academy_id, name, event_date, description |  |
| annual_event_study_plan_link | annual_event_id(FK), study_plan_item_id(FK) | 행사↔학습계획 연계, 상세 반영 방식은 Phase3 시점 확정 |

## U. offline_qna / vocab_test — 대면 질의응답·영단어시험 (스텁, 요구사항 확인 필요)

| 테이블 | 핵심 컬럼 | 비고 |
| --- | --- | --- |
| offline_qna_session | id, academy_id, student_id(FK), mentor_id(employee_id), session_date, notes | H(notice)의 온라인 QnA와 별개로 대면 세션 기록 추정, 확인 필요 |
| vocab_test_result | id, academy_id, student_id(FK), test_date, score | ⚠️ 가안 |

---

## 전체 도메인 간 관계 요약

```
account (A0) ── student_id/guardian_id/employee_id (다형 FK, 정확히 하나만)

★ 학생은 2단이다. 연도 종속 데이터는 전부 enrollment에 붙는다.

student (사람, 영구)                      ← unique_code, 상담이력·신상기록부만 여기
 ├─ student_guardian_link → parent_guardian (A2,A3)
 ├─ personal_record_card (S)              ← 사람 단위
 ├─ counseling_log (P)                    ← 사람 단위(입학예약 상담부터 연속)
 └─ student_enrollment (등록 건, 기수별 1인 N행)   ← student_no, rfid_no, is_current
      ├─ class_assignment → class_master(homeroom_teacher_id) (A4, M)
      ├─ penalty_point → penalty_item ← penalty_rule (V)
      ├─ billing → payment_receipt / refund / virtual_account (B)
      ├─ attendance_tagging_log(7종) ─배치─> attendance_daily_status(ABSENT는 여기만) (C)
      ├─ absence_reason ─┐
      ├─ regular_schedule ┼─> approval_request → approval_item (E)   ★ 승인은 전부 여기로
      ├─ firewall_request ┘        └─ firewall_violation → firewall_restriction (E')
      ├─ seat_leave_record → seat_master (C, M)
      ├─ meal_application(→billing) → meal_application_day (F)
      ├─ mock_exam_score, internal_grade_record(블로커), self_grading_response (G)
      ├─ qna_post, survey_response (H)
      ├─ study_plan_item → period_master (Q, M)
      ├─ daily_routine_log (R) ──┐
      │                          └─> penalty_rule 트리거 (V)  ★ 출결과 함께 penalty를 탄다
      ├─ study_time_log, daily_report (J)
      ├─ special_lecture_application, special_lecture_attendance (L)
      └─ admission_result (O)

admission_reservation → admission_waitlist → student(전환) (A6)
employee ── employee_role ── role ── permission (N)
notification_template ── notification_log (K)
annual_event (T) ──> study_plan_item 자동 반영 (Q)
```

**이 그림에서 읽어야 할 세 가지**
1. **승인이 한 곳(E)으로 모인다** — 사유신청·정기일정·방화벽이 각자 승인 컬럼을 갖지 않는다
2. **penalty(V)가 출결과 데일리루틴 양쪽에서 참조된다** — 그래서 `attendance` 하위에 두면 순환 참조가 난다
3. **사람 단위 vs 등록 건 단위가 갈린다** — 상담·신상기록부만 사람에 붙고 나머지는 전부 등록 건

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
| 대성전산 API(daesung) | **호출하지 않음 — 대체한다** | DSA를 프록시하는 게 아니라 우리 DB에서 직접 서빙. `integration/daesung`은 용도 소멸 |
| `TB_STUDENT_MST` / `TB_PDTL_RLS` 2단 구조 | **채택** | 학번·RFID를 등록 건에 붙이는 근거. A1 참고 |
| `RFID_NO` 비유니크 + 현재-플래그(`STAT_GB`) | **채택** | 이력이 쌓이므로. 안 거르면 퇴원생 카드가 통과 |
| `att_gn` 7종 코드(`S/T/A/D/N/C/R`) | **원본 그대로 채택** | 키오스크 응답값이 곧 저장값. 바꾸면 호환 구획이 깨짐 |
| `RES_GB`에 `E:식대` 존재 | **근거로 채택** | 레거시도 급식을 같은 청구 체계로 다뤘음 — 공용 결제 도메인의 방증 |

---

## §4 블로커 — 확정 전 관련 스키마/API 설계 금지

| 항목 | 상태 |
| --- | --- |
| **신상기록부 4종 양식** (I-17, 최우선) | 미정 — S 섹션. **회원가입 온보딩 착수 전 필수**. 강제 단계라 `account.status` 이분법과 충돌하는 문제도 같이 정리 필요 |
| **레거시 스키마 4종 미확보** | 출결·좌석/구역·지점·사유신청. **출결이 가장 급함**(DSA 호환 엔드포인트 10개가 여기 걸림). 단 API 계약에서 역산 설계 가능하므로 착수 블로커는 아님 |
| 명단 구분 항목 (PRD 4.9, I-1) | 운영팀 재정의 필요. **유일하게 우회 불가** 항목 |
| 알림 템플릿 문구 (I-4) | 미정 — K.`content_confirmed=false`로 발송 보류. **알림톡 사전심사(E-5, 최우선)가 여기 직렬로 물려 있어 가장 급하다** |
| 상벌점 자동부여 규칙 매핑 (I-5) | 미정 — V.`penalty_rule`. 수기 부여만 먼저 열고 엔진은 인터페이스만 |
| 자리이탈 두 채널 충돌 규칙 (I-16) | 미정 — C.`seat_leave_record`. **실시간 좌석표 UI는 2차 개발 이관 제안 상태**라 스코프 자체가 불확실 |
| 내신 성적 입력 양식 (I-11) | 미정 — G.`internal_grade_record` |
| 채팅 SDK 최종 선택 (E-6) | 미정 (SDK 사용 자체는 확정) — I 섹션. **벤더명을 패키지에 미리 박지 말 것** |
| 결제 법적요건 (전자금융거래법 등) | 미검토 — B 섹션 전체 |
| Zyxel 크레덴셜·제어 단위 (E-1) | 미정 — E'.`zyxel_site_id` |
| 민감정보 암호화 방식 | `birth_date`·`account_no`. ⚠️ 생년월일은 동명이인 구분 조회조건으로 쓰이면 인덱스를 못 탄다 — **조회에 쓰는지부터 확인** |
| 방화벽 적발 누적 기준 기간 | 미확인 — E'.`firewall_restriction`(연간/학기/영구?) |
| RBAC 축 정리 | 요구사항정의서는 5단계 권한등급, CLAUDE.md는 담당/행정선생님 2종 직무구분 — 서로를 포함 못 함 |
| 기존 재원생 데이터 이관 | **설계를 막는 블로커 아님**. 컷오버 직전에만 필요 |

## 해소된 블로커 (참고)

- **키오스크 payload·RFID 매핑 (D-2, 최우선)** → DSA 호환 API 제공으로 해소. 엔드포인트 25개 계약 확보 → 출결 Phase 1 착수 가능
- 방화벽 담당교사 매핑 → 반배정 자동연동 / 승인 타임아웃 → 10분 / 사전지정 UI → 불필요
- 급식 PG → 급식업체 기존 PG가 곧 대성 PG, 신규 선정 아님
- 상담 양식 → 목업 존재
- 학습계획 이행 표시(I-19) → ○/△/✗ 3단계, 교시 20분 편집은 반대로 확정