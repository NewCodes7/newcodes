# Claude Code 검증 게이트 (`.claude/hooks/`)

Claude Code가 만든 코드를 커밋 전에, 운영 서버 기준으로 검사하는 훅 모음이다.
등록은 `.claude/settings.json`에 있다. 게이트가 잡은 문제와 수정 내용은 `docs/검증게이트_수정이력.md`에 남긴다.

## 언제 무엇이 도는가

| 훅 이벤트 | 스크립트 | 하는 일 | 막는가 |
|---|---|---|---|
| SessionStart | `record-baseline.sh` | 세션 시작 시점에 이미 수정돼 있던 `src/` 파일 목록을 `.claude/.cache/baseline-files`에 저장한다. 게이트는 이 파일들을 빼고 **이번 세션의 변경만** 검사한다. | 아니오 |
| PostToolUse (Edit·Write·MultiEdit, `.java`만) | `post-edit-check.sh` | 편집 직후 증분 컴파일(`compileJava compileTestJava`). 실패하면 `additionalContext`로 알리기만 한다(리팩터링 중간 상태 고려). | 아니오 |
| Stop | `verify-gate.sh` | 아래 5단계 검사. 실패하면 exit 2로 실패 내용을 Claude에게 되먹여 고치게 한다. | 예 |

## Stop 게이트 단계 (`verify-gate.sh`)

이번 세션에 `src/` 변경이 없으면(대화·조회만 한 턴) 건너뛴다. 단계마다 실패하면 바로 멈춘다(fail-fast).

1. **컴파일**: `./gradlew compileJava compileTestJava`
2. **포맷/린트**: `./gradlew spotlessCheck`. 변경된 파일만 검사해 레거시 코드에 막히지 않는다. `spotlessApply`로 자동 수정할 수 있다.
3. **SAST**: SpotBugs + find-sec-bugs 리포트를 `sast-filter.py`가 걸러, **변경된 라인 위의 SECURITY 결함만** 실패로 본다.
4. **통합 테스트**: `./gradlew test` (PostgreSQL 필요, `.env` 로드).
5. **AI 리뷰**: `agent-review.sh`가 diff와 운영 맥락(`perf-context.sh` 출력)을 `claude -p --model sonnet`에 넘긴다. `review-parse.py`가 응답을 판정해, high 이슈가 하나라도 있으면 차단하고 low 이슈는 경고로만 보여 준다.

**연속 실패 상한**: 5회 연속 실패하면 자동 수정을 멈추고, 사람에게 확인을 요청하는 메시지를 남긴 채 게이트 미통과 상태로 끝낸다(`.claude/.cache/verify-attempts`). 무한 루프를 막기 위해서다.

## 막지 않고 통과시키는 경우 (의도된 fail-open)

| 상황 | 동작 | 이유 |
|---|---|---|
| 테스트 DB에 연결할 수 없음 | 통합 테스트를 건너뛰고 경고 | 인프라 문제를 코드 결함으로 오인하지 않으려고 |
| `claude` 미설치, 4분 타임아웃, 빈 응답, JSON 파싱 실패 | AI 리뷰 통과 | 컴파일·린트·SAST·테스트가 1차 방어선이다 |
| 운영 데이터량 조회 실패 | 마지막 캐시를 쓰고, 그것도 없으면 "미가용"으로 표시해 보수적으로 가정하게 함 | 네트워크 문제로 게이트가 멈추지 않게 |

이 게이트는 **로컬 Claude Code 세션에서만** 돈다. CI(`.github/workflows/deploy.yml`)는 이 게이트를 실행하지 않는다.

## AI 리뷰에 넣는 운영 맥락 (`perf-context.sh`)

AI 리뷰가 "기능은 맞지만 운영 사양에서는 위험한" 코드를 잡도록, 리뷰 프롬프트 앞에 두 가지를 붙인다.

1. **서버 사양**
   - JVM 힙은 `docker-compose.yml`의 `JAVA_OPTS` 기본값에서 **읽는다**(현재 `-Xms256m -Xmx512m`). 운영 `.env`가 `JAVA_OPTS`를 덮어쓰면 그 값이 우선이라는 점도 함께 알린다.
   - 호스트 사양은 코드로 읽을 곳이 없어 고정값이다: WAS 2 vCPU·2GB RAM, DB 2 vCPU·1GB RAM, HikariCP 풀 5. 바뀌면 이 스크립트와 `docs/operations/DB_LOAD_REDUCTION.md`를 같이 고친다.
   - 예전에는 힙을 스크립트에 하드코딩해 두었고, 2026-08-20의 `-Xmx 1024m → 512m` 변경(`load-test/results/2026-08-20-jvm-heap-sizing.md`)을 반영하지 못한 채 AI에게 "힙 1GB"를 알려 주고 있었다. 그래서 힙은 설정 파일에서 읽게 바꿨다.
2. **실제 데이터량**: 운영 `/api/admin/metrics/table-stats`에서 테이블별 추정 행 수와 크기를 가져온다.
   - `.env`의 `PERF_STATS_URL`·`PERF_STATS_TOKEN`을 쓰고, 토큰이 무효하면 `PERF_ADMIN_EMAIL`·`PERF_ADMIN_PASSWORD`로 로그인해 JWT를 다시 받아 `.claude/.cache/perf-token`에 캐시한다(`.cache`는 gitignore 대상).
   - **알려진 한계**: 통계 조회를 위해 운영 admin 자격 증명을 개발 환경 `.env`에 둔다. 통계 전용 읽기 권한 토큰으로 바꾸는 편이 안전하다.

## 수동 실행

같은 단계를 사람이 직접 돌릴 수 있다.

```bash
.claude/hooks/verify.sh          # 컴파일 + Spotless + SAST
.claude/hooks/verify.sh --fix    # Spotless 자동 수정 후 위와 동일
.claude/hooks/verify.sh -t       # + 통합 테스트
.claude/hooks/verify.sh -r       # + AI 리뷰 (토큰 비용 발생)
.claude/hooks/verify.sh -a       # 전부 (Stop 게이트와 같은 범위)
```

Claude Code 안에서는 `/verify` 슬래시 커맨드로도 실행할 수 있다.

## 파일

| 파일 | 역할 |
|---|---|
| `record-baseline.sh` | SessionStart: 기존 WIP 스냅샷 |
| `post-edit-check.sh` | PostToolUse: 증분 컴파일 알림 |
| `verify-gate.sh` | Stop: 5단계 게이트, 연속 실패 상한 |
| `agent-review.sh` | 5단계 AI 리뷰 실행 |
| `review-parse.py` | AI 리뷰 응답(JSON) 판정 |
| `perf-context.sh` | AI 리뷰에 넣을 서버 사양·데이터량 |
| `sast-filter.py` | SpotBugs 결과에서 변경 라인의 보안 결함만 추림 |
| `verify.sh` | 수동 검증 러너 (`/verify`) |
