# PulseOps Backend

Java 21 / Spring Boot 3.5.16 / PostgreSQL 16 기반 웹사이트 모니터링 실습 서비스입니다.
사이트 관리 API와 한 번 실행 후 종료하는 Worker가 같은 JAR에 들어 있습니다.
인증, 사용자, 알림, 결제, 내부 스케줄러는 없습니다.

## 구조

```text
src/main/java/com/pulseops/
  PulseOpsApplication.java
  site/       JPA 엔티티, 저장소, 요청·응답 DTO, 사이트 관리
  check/      URL 검증, DNS 주소 고정 HTTPS 검사
  worker/     PostgreSQL 선점, 검사 실행, Worker 전용 시작점
  web/        REST API, 예외 JSON, CORS
src/main/resources/
  application.yaml
  application-worker.yaml
  db/migration/V1__create_monitoring_tables.sql
src/test/     단위 테스트, 로컬 TLS 전송 테스트, 실제 PostgreSQL·프로세스 테스트
compose.yaml
Dockerfile
gradle/wrapper/
```

## 개발 환경과 실행

JDK 21 (`java -version`과 `JAVA_HOME` 모두 확인), Docker Engine/Desktop 및 Compose v2가 필요합니다.
Gradle 8.14.3 Wrapper가 포함되어 있어 Gradle 별도 설치는 필요 없습니다.
처음 빌드할 때 Gradle 및 Maven Central 다운로드가 필요합니다.

PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up -d --wait
.\gradlew.bat build
.\gradlew.bat bootRun
```

macOS/Linux에서는 `chmod +x gradlew` 후 `./gradlew`를 사용합니다.
VSCode에서는 `PulseOpsApplication`을 Java 21로 실행하면 됩니다.
기본 프로파일은 `local`, 포트는 8080입니다. API 모드는 자동 검사를 실행하지 않습니다.

Compose의 PostgreSQL은 `127.0.0.1:5432`에만 노출되고 named volume에 데이터를 보관합니다.
`docker compose down`은 데이터를 보존합니다. DB 기본값은 DB명/사용자 `pulseops`, 비밀번호 `pulseops-local`입니다.
이 비밀번호는 로컬 실습용입니다.

| 환경변수 | 기본값 / 용도 |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Compose의 DB 생성 정보 |
| `POSTGRES_PORT` | `5432`, 호스트 포트 |
| `DB_URL` | `jdbc:postgresql://localhost:5432/pulseops` |
| `DB_USERNAME`, `DB_PASSWORD` | `pulseops`, `pulseops-local` |
| `SPRING_DATASOURCE_URL` | 우선 적용되는 JDBC URL. Kubernetes에서는 PostgreSQL Service DNS 주소를 지정 |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | 우선 적용되는 DB 계정 정보 |
| `SERVER_ADDRESS` | `0.0.0.0`, 컨테이너의 모든 IPv4 인터페이스에서 요청 수신 |
| `SERVER_PORT` | `8080` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` |
| `HTTP_TIMEOUT_SECONDS` | `5`, 1~30 허용 |

Compose는 `.env`를 읽지만, 직접 실행한 Spring Boot는 `.env`를 자동으로 읽지 않습니다.
DB 정보를 변경했다면 VSCode 실행 환경 또는 셸에도 `DB_*` 값을 설정합니다.
이미 초기화된 volume에서는 `POSTGRES_*` 변경만으로 DB 계정이 바뀌지 않습니다.

API와 Worker 모두 DB 설정을 `SPRING_DATASOURCE_*` → 기존 `DB_*` → 로컬 기본값 순으로 적용합니다.
Kubernetes에서는 `SPRING_DATASOURCE_URL=jdbc:postgresql://<PostgreSQL-Service-DNS>:5432/pulseops`와
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 환경변수로 주입합니다.
Service 이름과 호스트 주소는 코드에 고정되어 있지 않습니다. `localhost`는 환경변수 미지정 시 로컬 개발용 기본값입니다.
기본 `local` 프로파일과 웹 서버를 시작하지 않는 `worker` 프로파일은 유지됩니다.
개발용 CORS 주소는 `CORS_ALLOWED_ORIGINS`로 배포 환경의 프론트엔드 Origin으로 변경할 수 있습니다.

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/pulseops'
$env:DB_USERNAME = 'pulseops'
$env:DB_PASSWORD = 'pulseops-local'
.\gradlew.bat bootRun
```

## API

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/v1/sites` | 최신 검사 상태 포함 목록, 검사 전 `UNKNOWN` |
| POST | `/api/v1/sites` | 등록, `201` 및 `Location` |
| PATCH | `/api/v1/sites/{id}` | 이름, 주기, 활성화 수정 |
| DELETE | `/api/v1/sites/{id}` | 이력 포함 삭제, `204` |
| GET | `/api/v1/sites/{id}/history?hours=24` | 1/6/24시간 또는 `minutes=1/5/30`으로 분 단위 이력, 오래된 순 |

차트 조회 기간은 1분·5분·30분·1시간·6시간·24시간을 지원합니다.
분 단위는 `/api/v1/sites/{id}/history?minutes=5`, 시간 단위는 `?hours=6`으로 조회합니다.
기간을 생략하면 최근 24시간입니다. `hours`와 `minutes`를 함께 지정하거나 지원하지 않는 값을 보내면 400입니다.
응답 JSON 구조는 동일합니다. 프론트엔드 기간 선택 버튼은 이 쿼리 파라미터에 연결하면 됩니다.

**전체 누적 차트**는 `/api/v1/sites/{id}/history?all=true`로 조회합니다.
시간이 지나도 오래된 점이 조회 범위에서 빠지지 않으며, 새 결과가 저장될 때마다 재조회하면 배열에 추가됩니다.
`all=true`는 `hours`/`minutes`와 동시에 사용할 수 없습니다. 기존 기간 조회는 최근 구간만 반환합니다.
DB는 원래부터 검사 이력을 덮어쓰지 않고 누적 저장하며, 사이트를 삭제할 때 이력이 삭제됩니다.
전체 조회는 실습 규모를 위한 기능으로 페이지 제한 없이 반환합니다. 장기 운영 시 페이지 조회/다운샘플링이 필요합니다.

등록 예시:

```json
{"name":"Google","url":"https://www.google.com","intervalSeconds":60}
```

수정 예시:

```json
{"name":"Google main","intervalSeconds":300,"enabled":true}
```

주기는 60/300/600만 허용합니다. 등록 시 즉시 검사 가능한 `nextCheckAt`을 설정합니다.
주기 변경 시 현재 시각 + 새 주기, 비활성 → 활성 전환 시 현재 시각으로 갱신합니다.
비활성 사이트는 검사 대상에서 제외하지만 `nextCheckAt`과 기존 이력은 보존합니다.
검사 도중 비활성화되면 진행 중인 검사 한 건은 저장될 수 있습니다.
PATCH에서 생략하거나 null인 필드는 유지합니다. 빈 PATCH는 현재 상태를 반환합니다.
URL은 수정할 수 없고, 알 수 없는 JSON 필드는 400입니다.

HTTPS 스킴/호스트 대소문자, 기본 443 포트, 빈 루트 경로, 경로의 `.`/`..`를 정규화하여
중복을 검사합니다. 서로 다른 URL이 같은 콘텐츠를 가리키는지는 판단하지 않습니다.
유효한 URL도 등록 시 DNS 조회가 실패하면 400입니다. ASCII 호스트만 지원합니다.

시각은 Java `Instant`와 PostgreSQL `TIMESTAMP WITH TIME ZONE`으로 다루고 ISO 8601 UTC `Z`로 반환합니다.
목록은 `id`순, 이력은 `checkedAt`, 동일 시각에서는 결과 `id`순입니다.
응답 시간은 연결·TLS·응답 헤더 수신까지 단조 시계로 측정합니다. 본문 다운로드 시간은 포함하지 않습니다.
리다이렉트를 따라가지 않으므로 3xx 자체는 UP입니다. 응답 없는 실패는 코드와 시간이 모두 null입니다.

오류는 다음 구조로 반환합니다. 400(검증), 404(사이트 없음), 409(URL 충돌), 500(예상하지 못한 오류)을 처리합니다.
500의 내부 예외는 로그에만 남깁니다.

```json
{"timestamp":"2026-10-22T00:00:00Z","status":400,"message":"Validation failed","path":"/api/v1/sites","details":["intervalSeconds: intervalSeconds must be 60, 300, or 600"]}
```

Swagger UI: <http://localhost:8080/swagger-ui/index.html>

OpenAPI JSON: <http://localhost:8080/v3/api-docs>

Health: <http://localhost:8080/actuator/health>

## Worker

```powershell
.\gradlew.bat bootJar
java -jar build/libs/app.jar --spring.profiles.active=worker
```

로컬에서 검사 결과를 계속 쌓으려면 별도 PowerShell 터미널에서 실행합니다.

```powershell
.\gradlew.bat bootJar
.\scripts\run-worker-loop.ps1
```

스크립트는 Worker 완료 후 60초를 기다려 다음 Worker를 실행합니다. 사이트별 검사 예정 시각은 그대로 준수합니다.
중지는 `Ctrl+C`입니다. `-Iterations 2`는 두 번 실행 후 종료하는 확인용 옵션입니다.
DB 설정은 현재 터미널의 `DB_*` 환경변수를 사용합니다.
API 프로세스만 실행하면 반복 검사가 시작되지 않습니다. Kubernetes에서는 이 스크립트 대신 CronJob을 사용합니다.
차트를 누적 표시하려면 프론트엔드에서도 `all=true` 이력을 주기적으로 재조회해야 합니다.

Worker 프로파일은 웹 서버와 Swagger를 시작하지 않습니다. 실행 시작 시점까지 검사 예정인
활성 사이트 ID를 읽고 순차 검사한 뒤 컨텍스트와 DB 풀을 닫고 코드 0으로 종료합니다.
네트워크 실패는 DOWN 결과로 저장하며 정상 작업으로 처리합니다.
DB 등 인프라 실패는 나머지 사이트를 계속 처리한 뒤 비정상 종료합니다.
검사 완료 시각 + 사이트별 현재 주기로 다음 검사를 예약하여 지연 실행 시 밀린 횟수만큼 몰아서 검사하지 않습니다.
검사 도중 PATCH로 미래 시각을 지정했다면 해당 예약을 보존합니다.

각 사이트 선점은 짧은 트랜잭션의 `FOR UPDATE SKIP LOCKED`와
`pg_try_advisory_lock(site_id)`로 수행합니다. 선점 직후 커밋하고, HTTP 요청 중에는
전용 DB 연결의 **세션 잠금만** 유지합니다. 결과 저장과 다음 시각 갱신은 하나의 짧은 트랜잭션입니다.
다른 Worker는 잠긴 사이트를 건너뜁니다. TTL 만료로 진행 중인 검사를 다시 선점하지 않습니다.
프로세스/DB 세션 종료 시 PostgreSQL이 잠금을 해제합니다. 연결 장애 시 DB가 종료를 감지할 때까지 재검사가 늦어질 수 있습니다.
잠금 해제 실패 시 연결을 폐기하여 잠긴 연결이 풀로 반환되지 않게 합니다.
세션 잠금이므로 **PgBouncer transaction/statement pooling은 사용할 수 없습니다**. 직접 DB 연결 또는 session pooling을 사용합니다.
Worker 하나는 HTTP 검사 중 DB 연결 하나를 사용합니다. 사이트 수가 많은 운영 서비스용 병렬 처리나 이력 보관 정책은 범위에 포함하지 않았습니다.

## SSRF 방어와 네트워크 통제

Apache HttpClient 5를 사용합니다. Java HttpClient 기본 DNS 조회와 별도 사전 조회만 조합하면
검증 주소와 연결 주소가 달라질 수 있어, 매 검사에 전용 connection manager와 고정 DNS resolver를 만듭니다.

- 등록 및 검사 시 HTTPS/443만 허용하고 사용자 정보, fragment, localhost 계열을 거부합니다.
- DNS의 모든 A/AAAA 주소를 검사하고 하나라도 내부·루프백·링크 로컬·메타데이터·예약 대역이면 거부합니다.
- IPv4 사설/공유/멀티캐스트/시험 대역과 IPv6 ULA·링크 로컬·NAT64·6to4 등 전환 대역을 차단합니다.
  IPv6는 native global-unicast 일부만 허용하는 보수적인 정책입니다.
- 검사 시 검증한 주소 배열만 실제 연결 resolver에 전달합니다. URL 호스트는 TLS SNI와 인증서 호스트 검증에 유지합니다.
- 자동 redirect/retry, cookie 관리, 본문 압축을 비활성화합니다. 시스템 프록시 설정을 가져오지 않습니다.
- 응답 헤더만 읽고 연결을 즉시 닫아 큰 본문을 메모리에 저장하거나 끝까지 소비하지 않습니다.
- 연결/소켓/응답 타임아웃을 설정합니다. 기본 5초는 연결·읽기별 제한이며 DNS와 여러 주소 시도를 포함한 전체 작업의 절대 시간 제한은 아닙니다.

애플리케이션 검증만으로 공개 인터넷 서비스의 안전을 보장하지 않습니다.
공인 IP가 내부 네트워크로 라우팅되거나 특수 IPv6 변환/네트워크 장비가 개입하는 경우는 애플리케이션에서 통제할 수 없습니다.
공인 대상의 의도치 않은 사용, DNS 지연, 응답 헤더 slow-drip 등의 자원 소진 위험도 남습니다.
인증·요청 제한이 없는 실습 API를 공개 인터넷에 바로 노출하지 마세요.

Kubernetes에서는 Worker를 분리하고 default-deny egress 정책을 적용한 뒤
DNS, PostgreSQL, 허용된 외부 HTTPS만 열어야 합니다. Pod/Service/노드/사내/클라우드 메타데이터 대역은 IPv4·IPv6 모두 제외합니다.
서비스 CIDR과 공인 주소를 사용하는 내부망도 환경에 맞춰 제외해야 합니다.
표준 NetworkPolicy는 DNS 이름 기반 외부 허용을 제공하지 않고 CNI와 NAT 적용 순서에 따라 동작이 달라질 수 있습니다.
필요하면 CNI의 FQDN 정책, egress gateway/방화벽 및 신뢰된 목적지 allowlist를 사용하고 실제 클러스터에서 차단을 검증하세요.

## 테스트

외부 네트워크 없이 실행하는 단위·TLS 테스트:

```powershell
.\gradlew.bat build
```

실제 PostgreSQL 테스트는 별도 테스트 DB를 사용하며 **테스트 DB의 사이트/이력을 초기화**합니다.
운영 DB를 테스트 대상으로 지정하지 마세요. H2나 Docker 가용성에 따른 자동 skip은 사용하지 않습니다.

```powershell
docker compose up -d --wait
docker compose exec -T postgres psql -U pulseops -d postgres -c 'CREATE DATABASE pulseops_test;'
.\gradlew.bat integrationTest
```

테스트 DB가 이미 있으면 CREATE DATABASE는 생략합니다. 변경 시 `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME`, `TEST_DATABASE_PASSWORD`를 설정합니다.
통합 테스트는 Flyway/JPA 실제 연결, REST CRUD·검증·이력·삭제 cascade, 독립 주기,
동시 Worker 선점, HTTP 중 열린 트랜잭션 부재, PATCH/삭제 경합, API 모드 검사 미실행,
Swagger/Health/CORS, 실제 JAR Worker 정상 종료 및 두 프로세스 동시 실행을 검증합니다.
로컬 TLS 서버 테스트는 DNS 주소 고정, 인증서 호스트 검증, redirect 미추적, 본문 미소비, 503 및 응답 타임아웃을 검증합니다.
테스트 인증서 `src/test/resources/probe.p12`와 `test-only` 비밀번호는 테스트 전용이며 배포 JAR에 포함되지 않습니다.

결과: `build/reports/tests/test/index.html`, `build/reports/tests/integrationTest/index.html`.
실행 검증 기록은 [docs/verification.md](docs/verification.md)에 있습니다.

## Docker와 향후 Kubernetes 연결

```powershell
docker build -t pulseops-be:local .
docker run --rm -p 127.0.0.1:8080:8080 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/pulseops -e DB_USERNAME=pulseops -e DB_PASSWORD=pulseops-local pulseops-be:local
docker run --rm -e DB_URL=jdbc:postgresql://host.docker.internal:5432/pulseops -e DB_USERNAME=pulseops -e DB_PASSWORD=pulseops-local pulseops-be:local --spring.profiles.active=worker
```

이미지는 Java 21 multi-stage build이며 런타임 UID 10001로 실행합니다.
`host.docker.internal` 예시는 Docker Desktop 기준이며 Linux에서는 실제 DB 주소나 컨테이너 네트워크를 사용합니다.

향후 CronJob에는 같은 이미지, args `--spring.profiles.active=worker`, schedule `* * * * *`를 지정합니다.
`concurrencyPolicy: Forbid`, `restartPolicy: Never`, 제한된 `backoffLimit`과 `startingDeadlineSeconds`를 권장합니다.
`activeDeadlineSeconds`는 사이트 수와 HTTP/DNS 지연을 고려해 지정합니다. 강제 종료된 검사는 재실행될 수 있습니다.
API는 Deployment로 실행하며 DB 설정은 Secret/환경변수로 전달하고 Health probe를 연결합니다.
CronJob 중복 방지는 클러스터 설정과 별개로 DB 잠금이 보장합니다.
현재 단계에서는 Kubernetes YAML과 React 파일을 생성하지 않습니다.

버전 근거: [Spring Boot 3.5 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Springdoc 호환표](https://springdoc.org/v2/).
