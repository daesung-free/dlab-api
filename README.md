# D.Lab 통합관리 백엔드 (dlab-api)

D.Lab(대성학원) 통합 플랫폼의 백엔드 API 서버. 재원생 앱(Flutter), 관리자 웹(React), 키오스크가 전부 이 서버 하나를 바라본다.

> 프로젝트 배경, 도메인 원칙, 미확정 사항 등은 `CLAUDE.md` 참고. 이 README는 "처음 클론했을 때 실행하는 법" 위주.

## 기술 스택

- Java 17
- Spring Boot (Web, Security, Data JPA, Validation)
- Gradle (Groovy)
- PostgreSQL (AWS RDS)
- Flyway (스키마 마이그레이션)
- Lombok

## 브랜치 전략

| 브랜치 | 용도 |
|---|---|
| `main` | 배포 브랜치 |
| `develop` | 개발 브랜치 (평소 작업은 여기서) |

기능 단위 작업은 `develop`에서 `feature/기능명` 브랜치를 따서 진행 후 `develop`으로 merge.

## 로컬 실행

### 1. 클론

```bash
git clone https://github.com/daesung-free/dlab-api.git
cd dlab-api
git checkout develop
```

### 2. 환경변수 설정

`src/main/resources/application-local.yml` 파일을 만들고 (git에 안 올라감, `.gitignore` 처리됨) DB 연결정보를 채운다:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<RDS 엔드포인트>:5432/<DB명>
    username: <계정>
    password: <비밀번호>
```

> RDS 인스턴스는 아직 준비 전 — 만들어지면 이 값들 공유 예정.

### 3. 빌드 및 실행

```bash
./gradlew bootRun
```

또는 IntelliJ에서 `DlabApiApplication` 실행.

### 4. API 문서 확인

앱 실행 후: `http://localhost:8080/swagger-ui.html`

## 프로젝트 구조

```
com.dlab
├── common/       공통 인증, 예외처리, 응답 포맷
├── domain/       도메인별 비즈니스 로직 (user, attendance, payment, meal ...)
└── integration/  외부 연동 (키오스크, 대성전산, Zyxel, PG사 등)
```

패키지 구조·도메인별 상세 책임은 `CLAUDE.md` §5 참고.

## 관련 레포

- `dlab-admin-web` — 관리자 웹 (React)
- `dlab-app` — 학생·학부모 앱 (Flutter)
