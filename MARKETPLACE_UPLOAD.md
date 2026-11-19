# JetBrains Marketplace 업로드 입력값

업로드 페이지: https://plugins.jetbrains.com/plugin/add  (JetBrains 계정 로그인, DB Comment Search와 같은 vendor `sshhhdev` 사용)

## 1. Upload plugin 폼

| 항목 | 입력값 |
|---|---|
| **Vendor** | `sshhhdev` (DB Comment Search 때 만든 개인 vendor) |
| **Plugin file** | `build/distributions/JspJump-1.0.0.zip` (zip이 잠겨 갱신이 안 되면 IntelliJ를 끈 뒤 `gradlew.bat buildPlugin` 다시 실행) |
| **License** | `MIT License` |
| **Tags** | `Navigation`, `Web`, `JavaScript` (선택: `Spring`) |
| **Source code URL** | `https://github.com/sshhhGit/JspJump` |
| **Channel** | `Stable` |

> 이름·설명·변경 내역·아이콘·호환 버전은 zip 안의 `plugin.xml`에서 자동으로 읽는다.

## 2. zip에서 자동으로 채워지는 값 (확인용)

| 항목 | 값 |
|---|---|
| Plugin ID | `com.sshhhdev.jspjump` (한 번 올리면 변경 불가) |
| Name / Version | `JSP Jump` / `1.0.0` |
| Compatibility | build 253+ (2025.3 이상), 상한 없음 |
| Dependencies | `com.intellij.modules.platform`만 → IntelliJ IDEA Community / Ultimate 및 다른 JetBrains IDE에도 설치 가능 |
| Icon | `META-INF/pluginIcon.svg`, `pluginIcon_dark.svg` |
| Description / Change notes | `plugin.xml`의 `<description>`, `<change-notes>` (영문 + 한국어) |

## 3. 승인 후 플러그인 페이지에서 추가하면 좋은 것

| 항목 | 내용 |
|---|---|
| **Screenshots** | ① 점프 모드(파란 상자 + 상단 안내) ② IDE에서 JSP 줄이 열린 화면 ③ 후보 선택 팝업 ④ 북마크 추가 페이지. **회사 화면·소스가 보이지 않는 샘플 프로젝트로 캡처** |
| **Issue tracker** | `https://github.com/sshhhGit/JspJump/issues` |
| **Website** | `https://github.com/sshhhGit/JspJump` |

## 4. 올리기 전 체크리스트

- [x] 실제 IntelliJ 2026.3에 설치해 점프·후보 팝업·창 전환·포트 자동 탐색·여러 프로젝트 선택 확인
- [x] 다른 구조 확인: Spring 컨트롤러 / Tiles / Struts / 여러 그리드 / 폼 태그 / 메시지 키 (하네스 + 시험 페이지)
- [x] `gradlew.bat verifyPlugin` 결과 2025.3·2026.3 Compatible, 경고 없음 (2026-11-19)
- [ ] 스크린샷에 회사 정보가 없는지 확인
- [ ] GitHub `main`이 업로드하는 zip과 같은 커밋인지 확인

## 5. 심사에서 물어볼 수 있는 점 (답변 준비)

- **로컬 HTTP 엔드포인트**: IDE 내장 서버(`localhost:63342`)에 `/jspjump`를 추가한다. Host가 localhost가 아니면 거부하고,
  `POST`는 설정의 허용 Origin(기본 `http://localhost:*`, `http://127.0.0.1:*`)에서 온 요청만 처리한다. 외부로 보내는 통신은 없다.
- **java.awt.Robot**: Windows에서 IDE 창을 앞으로 가져올 때만, 마우스를 현재 위치로 다시 옮기는 입력 한 번을 만든다(화면 변화 없음).
  설정의 "Bring the IDE window to front"를 끄면 사용하지 않는다.
- **브라우저 열기**: 플러그인 설치 직후 한 번만 북마크 추가 페이지(`http://localhost:<port>/jspjump/install`)를 연다.

## 6. 다음 버전부터 (CLI 업로드)

1. Marketplace → 프로필 → **My Tokens**에서 토큰 발급
2. `build.gradle.kts`에 `intellijPlatform { publishing { token = providers.environmentVariable("PUBLISH_TOKEN") } }` 추가
3. `version`과 `plugin.xml`의 `change-notes` 갱신 후:

```
$env:PUBLISH_TOKEN="<토큰>"; ./gradlew publishPlugin
```
