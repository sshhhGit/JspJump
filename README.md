# JSP Jump

브라우저(크롬)에서 **로컬 서버로 띄운 화면**의 버튼·입력칸·AUIGrid 컬럼을 클릭하면, 로컬 IntelliJ가 그 요소를 정의한
**JSP 파일의 해당 줄**을 여는 IntelliJ 플러그인 + 북마클릿입니다. 개발자 도구의 "요소 선택"처럼
**점프 모드를 켜고 한 번 클릭**하는 방식이라 크롬의 Alt/Ctrl/Shift+클릭 기본 동작과 겹치지 않습니다.

*English summary below.*

## 동작 방식

```
[크롬: localhost:8080 화면] --북마클릿(점프 모드, 클릭 1회)--> POST http://localhost:63342/jspjump
                                                              │ IntelliJ 내장 서버 + JSP Jump 플러그인
                                                              ▼
                                   프로젝트 JSP 검색 → 해당 줄 열기 (후보 여러 개면 IDE 팝업)
                                   결과 JSON → 브라우저 토스트
```

모든 통신은 내 PC 안(localhost)에서만 일어납니다.

## 빠른 시작 (3단계)

1. **플러그인 설치** — IntelliJ *Settings | Plugins | ⚙ | Install Plugin from Disk...* 에서 `JspJump-x.y.z.zip` 선택
   (재시작 없이 바로 적용됩니다)
2. **북마크 추가 (브라우저당 처음 한 번)** — JSP Jump를 설치하면 북마크 추가 페이지(<http://localhost:63342/jspjump/install>)가
   브라우저에 자동으로 열리고 IDE에 안내 알림이 뜹니다(재설치 포함, IDE를 그냥 켤 때나 버전 업데이트 때는 뜨지 않음).
   파란 **JSP Jump** 버튼을 크롬 북마크바로 끌어다 놓으세요.
   (다시 열기: *Tools | JSP Jump: Add Browser Bookmark...*)
   (북마크바가 안 보이면 `Ctrl+Shift+B`)
3. **사용** — 로컬 서버 화면에서 북마크 **JSP Jump** 클릭 → 원하는 요소 클릭 → IntelliJ에서 JSP 해당 줄이 열림

> **왜 북마크를 따로 추가해야 하나요?** 화면의 클릭을 가로채고 요소 정보를 읽는 일은 그 페이지 안에서 도는
> 스크립트만 할 수 있습니다. IntelliJ 플러그인은 크롬 화면에 손댈 수 없으므로, 브라우저 쪽에 스크립트를 불러오는
> 북마크(북마클릿)가 하나 필요합니다. 북마크에는 "IDE에서 스크립트를 받아 오라"는 짧은 코드만 들어 있어
> 플러그인을 업데이트해도 다시 만들 필요가 없습니다.

드래그가 안 되면 북마크를 새로 만들고 URL에 아래를 그대로 붙여 넣으세요.

```text
javascript:(function(d){var k='jspjump.port',P=[],r=0,i=0,p;try{r=+localStorage.getItem(k)||0}catch(e){}if(r)P.push(r);for(p=63342;p<=63361;p++)if(p!==r)P.push(p);(function n(){if(i>=P.length){alert('JSP Jump: IntelliJ를 찾을 수 없습니다. IntelliJ 실행 여부와 JSP Jump 플러그인 설치를 확인하세요.');return}var s=d.createElement('script');s.src='http://localhost:'+P[i++]+'/jspjump/bookmarklet.js?t='+Date.now();s.onerror=function(){s.parentNode.removeChild(s);n()};d.body.appendChild(s)})()})(document)
```

IDE에 연결하지 않고 스크립트 전체를 북마크에 넣고 싶으면 `node bookmarklet/build.js` 실행 후
`bookmarklet/dist/bookmarklet-full.txt` 내용을 URL로 사용합니다.

### IDE 포트는 자동으로 찾습니다

IntelliJ 내장 서버 포트는 기본 **63342**지만 고정은 아닙니다.

- JetBrains IDE(IntelliJ, WebStorm, DataGrip…)를 여러 개 켜면 먼저 뜬 IDE가 63342를 쓰고 다음 IDE는 63343, 63344…를 씁니다.
- *Settings | Build, Execution, Deployment | Debugger | Built-in server port* 에서 바꿀 수도 있습니다.

북마클릿은 ① 이 사이트에서 마지막으로 성공한 포트 → ② 63342~63361 순서로 JSP Jump가 설치된 IDE를 찾고,
여러 IDE가 응답하면 클릭한 요소의 JSP를 실제로 찾은 IDE에서 엽니다. 포트를 직접 바꿀 때는 63342~63361 사이로 두세요.

## 사용법

1. 로컬 서버 화면에서 북마크 **JSP Jump** 클릭 → 상단에 "요소를 클릭하세요" 배너, 마우스 아래 요소가 파란 상자로 표시
2. 원하는 버튼·입력칸·라벨·그리드 헤더/셀 클릭 → IntelliJ가 앞으로 오고 JSP 해당 줄이 열림, 점프 모드 자동 종료
3. 후보가 여러 개면 IntelliJ에 목록 팝업이 뜨고, 고르면 이동
4. `Esc` 또는 북마크를 다시 누르면 취소
5. 결과는 화면 오른쪽 아래 토스트: 성공(파일:줄) / 후보 n개 / 못 찾음(사용한 단서·시도한 검색) / 연결 실패

## 매칭 규칙 (우선순위 순)

| 클릭 대상 | 사용하는 단서 | JSP 검색 |
|---|---|---|
| AUIGrid 헤더/셀 | 그리드 id, `dataField`(AUIGrid API로 헤더 → 컬럼 확인) | `.addColumnCustom("필드"`, `.addColumn("필드"`, `dataField: "필드"` (함수명 설정 가능) → 없으면 헤더 텍스트 |
| id 있는 요소 | `id` | `id="x"`, `id=x`, `id=${접두어}x` (EL 접두어는 렌더링 id의 끝부분과 비교) |
| name 있는 요소 | `name` | `name="x"`, `name=x` |
| 버튼·라벨·th | 표시 텍스트 / 버튼 value | `value="조회"`, `>조회<` (공백·`&nbsp;`·`<br>` 무시) |
| 위가 모두 실패 | 가장 가까운 id 있는 조상 | 조상 id로 재시도 |

- **JS 파일까지 추적**: 화면 JSP가 `<script src="/js/order/OrderGrid.js">`로 불러오는 JS를 그 JSP 바로 다음에 검색하고, 그래도 없으면 다른 JSP → 프로젝트 JS 전체 순서로 넓힙니다. 그리드 컬럼이 JS에 `addColumnCustom("orderNo", msg.orderNo, ...)`처럼 정의돼 있어도(헤더가 변수여도) `dataField`로 찾습니다.
- **화면 JSP 우선**: URL(`/order/OrderListM.do` → `OrderListM.jsp`)과 조상 요소 id(예: `<div id="OrderListM">`)와
  같은 이름의 JSP(와 그 JSP가 include하는 파일)를 먼저 찾고, 없으면 프로젝트 전체 JSP로 넓힙니다.
- 같은 문구가 한 파일에 여러 번 있으면, 클릭한 요소의 조상(form/div id) 안에 있는 쪽을 우선합니다.
- **여러 프로젝트가 열려 있으면 화면을 띄운 프로젝트를 우선합니다.** IntelliJ에서 실행한 Tomcat이면 요청한 페이지의
  포트(예: `localhost:8084`) → 그 Tomcat의 배포 경로(`docBase`) → 그 경로를 가진 프로젝트를 찾아 먼저 검색합니다.
  화면 이름이 같은 JSP(`UserM.jsp` 등)가 다른 프로젝트에 있어도 엉뚱한 프로젝트가 열리지 않습니다. 찾지 못하면 다른 프로젝트로 넓힙니다.
  (IntelliJ 밖에서 띄운 서버는 이 정보가 없어서 가장 잘 맞는 결과가 나온 프로젝트에서 엽니다.)

## 설정 (Settings | Tools | JSP Jump)

- 요청 허용 / IDE 창 앞으로 가져오기
- 허용 Origin (기본 `http://localhost:*`, `http://127.0.0.1:*` 등. 사내 개발 서버 주소로 접속한다면 추가)
- 검색 대상 JSP glob (기본 `**/*.jsp`, `**/*.jspf`, `**/*.tag`), JS glob (기본 `**/*.js`), 제외 glob (`**/target/**`, `**/*.min.js`, `**/AUIGrid/**` 등)
- 그리드 컬럼 추가 함수명 (기본 `addColumnCustom, addColumn, addColumnInnerColspan`)

## 문제 해결

| 증상 | 원인 / 해결 |
|---|---|
| "IntelliJ를 찾을 수 없습니다" | IntelliJ가 꺼져 있거나 플러그인이 비활성. 포트를 63342~63361 밖으로 바꾼 경우에도 발생 |
| "Origin not allowed" | 회사 IP·호스트명(`http://192.168.0.10:8080` 등)으로 접속한 경우. IDE 알림의 **Allow** 또는 설정의 허용 Origin에 추가 |
| "찾지 못했습니다" | 토스트에 보낸 단서와 시도한 검색이 표시됩니다. JSP에 그 id·name·문구가 그대로 없으면(스크립트로 만든 요소 등) 찾을 수 없음 → 감싸는 영역을 클릭해 보세요. 프로젝트가 IDE에 열려 있어야 합니다 |
| IDE 창이 앞으로 오지 않고 작업표시줄에서 깜빡임 | Windows는 백그라운드 프로그램의 창 전환을 막습니다. JSP Jump는 입력 이벤트(마우스를 제자리로 이동)로 이를 풀고, 그래도 안 되면 창을 잠깐 "항상 위"로 올립니다. 그래도 깜빡이기만 하면 작업표시줄의 IntelliJ를 누르세요(파일은 이미 열려 있음). 설정에서 "Bring the IDE window to front" 끄기 가능 |
| 북마크를 눌러도 반응 없음 | `https` 페이지나 CSP로 외부 스크립트를 막는 사이트. 로컬 개발 서버(`http://localhost`)에서 사용 |
| 연결 상태 확인 | <http://localhost:63342/jspjump/install> 의 **연결 테스트** 버튼, 또는 `/jspjump/ping` |

## 보안

- 플러그인은 IntelliJ 내장 서버(`localhost:63342`)에만 엔드포인트를 추가합니다. Host 헤더가 localhost가 아니면 거부됩니다.
- `POST /jspjump`는 설정의 허용 Origin에서 온 요청만 처리합니다. 다른 사이트에서 보낸 요청은 파일을 열지 않습니다.
- 북마클릿은 클릭한 요소의 태그·id·name·표시 텍스트·그리드 컬럼과 페이지 경로만 보냅니다(입력값은 보내지 않음).

## 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/jspjump` | 단서 JSON(`Content-Type: text/plain`) → 검색·열기, 결과 JSON |
| GET | `/jspjump/ping` | 상태 확인 |
| GET | `/jspjump/bookmarklet.js` | 북마클릿 스크립트 |
| GET | `/jspjump/install` | 북마크 추가 안내 페이지(연결 테스트·문제 해결 포함) |

## 빌드

- JDK: IntelliJ 번들 JBR 21 (`gradle.properties`의 `org.gradle.java.home`)
- `gradlew.bat buildPlugin` / `gradlew.bat verifyPlugin` (설치된 2025.3, 2026.3으로 호환성 검사)
- 경로는 `gradle.properties`의 `localIdePath`, `verifyIdePath2`에서 바꿉니다.

---

## English

**JSP Jump** lets you click a button, input or AUIGrid column on a page served by your local server and jumps to the
JSP line that defines it in IntelliJ IDEA.

1. Install the plugin zip (*Settings | Plugins | Install Plugin from Disk*).
2. When JSP Jump is installed, the bookmark page opens in your browser with an IDE notification (again:
   *Tools | JSP Jump: Add Browser Bookmark...*, or <http://localhost:63342/jspjump/install>). Drag the
   **JSP Jump** button to the bookmarks bar. This is needed once per browser: only a script running in the page can
   intercept the click.
3. On your local page click the bookmarklet (jump mode), then click an element. `Esc` cancels.
4. The IDE opens the matching JSP line; with several candidates a chooser popup appears. A toast in the page shows
   the result or the clues that were used when nothing was found.

Matching uses `id` (also `id=${prefix}name` EL prefixes), `name`, AUIGrid `dataField` / header text
(`.addColumnCustom("field", ...)`, `dataField: "field"`), visible text and the nearest ancestor id. JSP files named after
the URL or the screen root element are searched first. Allowed origins, search globs and grid column function names are
configurable in *Settings | Tools | JSP Jump*. All traffic stays on `localhost`.

The IDE built-in server port is 63342 by default but changes when several JetBrains IDEs run (63343, ...) or when it is
changed in the settings. The bookmarklet finds the IDE on ports 63342–63361 automatically and remembers the last one.

## License

MIT
