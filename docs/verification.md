# 실행 검증 기록

검증일: 2026-10-22 (Asia/Seoul)

빈 작업 폴더에서 구현했으며 기존 파일 충돌은 없었습니다.
환경: Windows PowerShell, Temurin Java 21, Docker Desktop Linux engine, PostgreSQL 16.

## 빌드 및 자동 테스트

실행: `gradlew.bat build integrationTest --no-daemon`

결과: **BUILD SUCCESSFUL**, 총 **65개 성공 / 실패 0 / 오류 0 / skip 0**.

| 테스트 | 수 | 검증 |
| --- | ---: | --- |
| UrlPolicyTest | 42 | URL 형식, 사설·루프백·메타데이터·예약 IPv4/IPv6, 혼합 DNS, 검사 시 재검증 |
| HttpProbeTest | 9 | 2xx/3xx/4xx/5xx 분류, 응답 시간 측정, 네트워크 실패 null 처리 |
| HttpsTransportTest | 4 | 실제 로컬 TLS 소켓, 고정 주소 연결과 원래 TLS 호스트, 인증서 불일치 차단, redirect·본문 미소비, 503, 타임아웃 |
| PostgresIntegrationTest | 9 | 실제 PostgreSQL/Flyway, CRUD/검증/409/cascade, 분·시간별 정렬 이력, 전체 누적 이력, 독립 주기, PATCH 경합, 중복 Worker, 트랜잭션 부재, API 모드, Swagger/Health/CORS |
| WorkerProcessIntegrationTest | 1 | 실제 JAR Worker 프로세스 정상 종료, 웹 서버 미시작, 두 프로세스 동시 실행 후 결과 한 건 |

검증에 H2를 사용하지 않았습니다. 테스트 DB는 `pulseops_test`이며 Flyway V1 `success=true`를 직접 조회했습니다.
동시성 테스트는 첫 Worker의 HTTP 단계를 latch로 멈춰 두 번째 Worker를 겹쳐 실행합니다.
첫 검사 중 다른 세션의 선점이 실패하고 `idle in transaction` 세션이 0임을 확인했습니다.
분 단위 조회 추가 후 전체 테스트를 재실행했습니다. `minutes=1/5/30` 기간 필터와 정렬,
기존 시간 단위 및 기본 24시간 조회, 잘못된 값과 hours/minutes 동시 지정 시 400도 검증했습니다.

전체 누적 조회 추가 후에도 전체 빌드와 테스트가 통과했습니다.
`all=true`에서 2일 전 이력을 보존한 채 Worker를 두 번 실행하면 결과가 1→2→3건으로
누적되는지 확인했고, 기간 파라미터와 혼합하면 400인지 검증했습니다.
`scripts/run-worker-loop.ps1 -DelaySeconds 1 -Iterations 2`를 테스트 DB 대상으로
실행하여 실제 Worker 프로세스 두 번의 실행·정상 종료도 확인했습니다.
이는 유한 실행 검증이며 반복 Worker를 상시 실행해 둔 것은 아닙니다.

리포트:

- `build/reports/tests/test/index.html`
- `build/reports/tests/integrationTest/index.html`
- `build/worker-process-{bootstrap,first,second}.log`

## 실제 HTTP API와 외부 검사

빌드한 `build/libs/app.jar`를 API 모드로 임시 포트 18080에 실행했습니다.
Health `UP`, Swagger UI HTTP 200, OpenAPI의 모든 요청 경로를 확인했습니다.
API로 `https://example.com/`을 등록한 직후 상태는 UNKNOWN이었습니다.
같은 JAR의 Worker 프로세스를 실행하고 REST API로 이력을 조회했습니다.

```json
{
  "checkedAt": "2026-10-22T01:13:39.44426Z",
  "status": "UP",
  "httpStatusCode": 200,
  "responseTimeMs": 589
}
```

외부 DNS/TLS/HTTP를 실제로 사용한 결과이며, 이를 mock 결과로 대체하지 않았습니다.
실행 후 임시 API 프로세스를 종료하고 등록한 사이트와 검사 이력을 삭제했습니다.
원본 검증 결과는 `build/smoke-summary.json`, API/Worker 로그는 `build/smoke-*.log`에 있습니다.
외부 인터넷의 모든 장애 유형과 실제 Kubernetes 네트워크 정책은 검증하지 않았습니다.

## Docker

`docker compose up -d --wait`: PostgreSQL 16 healthy, `127.0.0.1:5432` 바인딩 확인.

`docker build -t pulseops-be:local .`: Linux Java 21 이미지 안에서 Gradle Wrapper로 컴파일 및 bootJar 성공.
이미지 태그: `pulseops-be:local`.

해당 이미지의 Worker를 Compose 네트워크에서 실행하여 PostgreSQL 연결,
Flyway 검증, 웹 서버 미시작, DB 풀 종료 및 프로세스 종료 코드 0도 확인했습니다.

Gradle 8.14.3 공식 배포 SHA-256을 Wrapper 설정에 고정했습니다.
PostgreSQL 개발 컨테이너와 named volume은 후속 개발을 위해 유지했습니다.
초기 검증 직후에는 임시 API 서버와 정기 검사를 종료했습니다.

### 후속 로컬 누적 검사 실행

사용자 요청으로 로컬 반복 Worker를 백그라운드에서 실행했습니다.
2026-10-22 11:05~11:06 (Asia/Seoul)에 실제 사이트 3의 이력이 기존 1건에서
2건, 다음 자동 실행 후 3건으로 증가한 것을 DB와 `history?all=true` API에서 확인했습니다.
사이트 4의 첫 검사 이력도 저장됐습니다. 반복 Worker는 실행 상태로 유지했습니다.
실행 PID는 `build/local-worker-loop.pid`, 로그는 `build/local-worker-loop*.log`에 있습니다.
이 프로세스는 로컬 실행이며 컴퓨터 재시작 시 자동 복구되지 않습니다.
중지하려면 PID에 해당하는 프로세스를 확인한 뒤 종료합니다.

```powershell
$loopProcessId = [int](Get-Content build/local-worker-loop.pid)
$loopProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $loopProcessId"
if ($loopProcess.CommandLine -match 'run-worker-loop\.ps1') {
    Stop-Process -Id $loopProcessId
}
```

## 범위와 한계

React/Kubernetes 리소스 YAML은 생성하지 않았습니다.
Kubernetes CronJob 설정 지침, DNS 고정 방식, 세션 잠금 제약,
남은 SSRF·자원 소진 위험 및 필요한 egress 통제는 README에 명시했습니다.
실제 Kubernetes 배포, CNI/NetworkPolicy 검증, 클라우드 메타데이터 실환경 접근 실험은 수행하지 않았습니다.
