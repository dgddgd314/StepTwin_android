# 지도 연동 (Kakao Map) 안내

안드로이드는 **지도 캔버스** 역할이다. 서버가 내려주는 좌표 배열을 카카오 지도 위에
Polyline / Marker 로 그린다.

## 1. 준비물: 카카오 네이티브 앱 키

1. [Kakao Developers](https://developers.kakao.com) 에서 앱 생성
2. **네이티브 앱 키** 발급
3. 플랫폼 > Android 에 패키지명 `com.example.steptwin` 과 키 해시 등록
4. `local.properties` 에 키를 넣는다 (이 파일은 커밋되지 않는다):

```properties
KAKAO_NATIVE_APP_KEY=여기에_네이티브_앱_키
SERVER_BASE_URL=http://172.30.1.66:8000/
```

키가 비어 있어도 **빌드는 되지만** 지도 인증에 실패해 지도가 뜨지 않는다.

## 2. 서버 주소 주의사항

- 안드로이드에서 `localhost` / `127.0.0.1` 은 폰 자신을 가리키므로 쓰면 안 된다.
- 폰에서 접속 가능한 호스트 IP 를 쓴다. 기본값: `http://172.30.1.66:8000/`
- 값은 `local.properties` 의 `SERVER_BASE_URL` 로 덮어쓸 수 있다.
- HTTP(비 TLS) 라서 `AndroidManifest.xml` 에 `android:usesCleartextTraffic="true"` 를 켜 두었다.

## 3. 서버 API 계약

### GET `/api/v1/health`
연결 확인용. `{ "status": "ok" }` 를 기대한다.

### POST `/api/v1/routes/preview`
지도에 그릴 데이터를 받는다. 요청 바디는 **출발지/도착지(Place)** + 선택 `preferences`:

```json
{
  "origin":      { "name": "청량리역",   "coordinate": { "latitude": 37.5804, "longitude": 127.0468 } },
  "destination": { "name": "경희의료원", "coordinate": { "latitude": 37.5936, "longitude": 127.0516 } },
  "preferences": { "avoid_stairs": true, "stair_weight": 1.0, "slope_weight": 0.7, "corner_weight": 0.4, "walking_speed_mps": 1.15 }
}
```

- `origin` / `destination` 필수. 각각 `{ name, coordinate:{latitude,longitude} }` 구조.
- `preferences` 선택(생략 시 서버 기본값). 앱은 TUG 보행 취약도(`TugWeights`)를 이 값으로 변환해 보낸다.
- 잘못된 바디는 **HTTP 422**로 거절된다. (스키마는 서버 `GET /openapi.json` 에서 확인 가능)

응답:

```json
{
  "segments": [
    {
      "kind": "custom_walk",
      "geometry": [
        { "latitude": 37.5665, "longitude": 126.978 },
        { "latitude": 37.5644, "longitude": 126.9784 }
      ],
      "render": { "color": "#16A34A", "pattern": "dashed" }
    },
    {
      "kind": "transit",
      "geometry": [
        { "latitude": 37.5616, "longitude": 126.9812 },
        { "latitude": 37.5554, "longitude": 126.9853 }
      ],
      "render": { "color": "#2563EB", "pattern": "solid" }
    }
  ],
  "markers": [
    {
      "kind": "shade_shelter",
      "coordinate": { "latitude": 37.5644, "longitude": 126.9784 },
      "icon": "parasol"
    }
  ]
}
```

## 4. 렌더링 규칙

| segment.kind | 선 색 / 모양 |
| --- | --- |
| `custom_walk` | 초록(`#16A34A`) 점선 |
| `transit` | 파랑(`#2563EB`) 실선 |
| 그 외 | 회색 실선 |

- 색은 `render.color` 가 있으면 그 값을, 없으면 kind 기본색을 쓴다.
- `render.pattern == "dashed"` 면 점선 처리한다.

| marker.kind | 마커 아이콘 |
| --- | --- |
| `shade_shelter` | 파라솔 (`ic_marker_parasol`) |
| `stairs_avoided` | 계단 회피 (`ic_marker_stairs`) |
| 그 외 | 기본 마커 (`ic_marker_default`) |

## 5. 코드 위치

- 화면/렌더링: `ui/map/MapRouteScreen.kt` (카카오 `MapView` 를 Compose `AndroidView` 로 호스팅,
  `drawPreview()` 에서 Polyline/Marker 를 그린다)
- 상태/호출: `ui/map/MapRouteViewModel.kt`
- 네트워킹: `data/remote/RouteApi.kt` (health + preview + DTO→도메인 변환)
- 저장소: `data/repository/RoutePreviewRepositoryImpl.kt`
- 도메인 모델: `domain/preview/RoutePreview.kt`
- SDK 초기화: `StepTwinApplication.kt` 의 `KakaoMapSdk.init`
- 빌드 설정: `settings.gradle.kts`(카카오 maven repo), `app/build.gradle.kts`(의존성 · 앱키/서버주소 주입)

지도 탭("맞춤 길찾기")을 열면 자동으로 health 확인 → preview 호출 → 지도에 그린다.
상단 배너의 **경로 새로고침** 버튼으로 다시 불러올 수 있다.

## 6. 겪은 이슈와 해결 (친구가 똑같이 막힐 수 있는 지점)

실제 폰(삼성 arm64)에서 지도 + 경로 렌더링까지 검증 완료. 도중 막혔던 4가지:

### (1) 지도가 안 뜨고 `MapAuthException(403)`
- **원인:** 앱키·패키지명·키해시가 다 맞아도, 카카오맵 **API 사용 설정이 꺼져 있으면** 403.
- **해결:** Kakao Developers → **제품 설정 → 카카오맵 → 활성화 ON**. (반영에 수 분)

### (2) 앱이 실행 즉시 크래시 (`UnsatisfiedLinkError`, `libK3fAndroid.so ... EM_AARCH64`)
- **원인:** 카카오 지도 SDK는 **arm64/armeabi 네이티브 라이브러리만** 제공. x86/x86_64 에뮬레이터엔 `.so` 가 없다.
- **해결:** **실제 ARM 폰**(또는 arm64 에뮬레이터)에서 실행. 코드에서는 `KakaoMapSdk.init` 를 `try/catch(Throwable)`
  로 감싸고 `MapSupport.available` 플래그로 미지원 기기에서는 안내만 표시하도록 처리해 뒀다.

### (3) 경로/마커를 그릴 때 네이티브 SIGSEGV (`PatternBase::makeImage()`)
- **원인:** 카카오 마커/패턴은 네이티브에서 **비트맵**을 요구한다. **벡터 드로어블(XML)** 을 넘기면
  `makeImage()` 가 null 을 반환하며 세그폴트. (Java 예외가 아니라 로그캣에 FATAL 로 안 잡힘 → 확인은 tombstone/`F DEBUG`)
- **해결:** `MapRouteScreen.rasterize()` 로 드로어블을 **런타임에 비트맵으로 변환**한 뒤
  `LabelStyle.from(bitmap)` / `RouteLinePattern.from(bitmap, distance)` 에 넘긴다.

### (4) `POST /routes/preview` 가 HTTP 422
- **원인:** 요청 바디를 잘못 보냄(취약도만 보냈음). 서버는 `origin`/`destination`(Place) 를 요구.
- **해결:** 위 3장의 요청 형식대로 전송. 스키마 확인은 `GET http://172.30.1.66:8000/openapi.json`.

> 키해시(디버그) 확인: PowerShell 에서 keytool 로 뽑는다.
> `keytool -exportcert -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android -file cert.der`
> 후 `[Convert]::ToBase64String([Security.Cryptography.SHA1]::Create().ComputeHash([IO.File]::ReadAllBytes("cert.der")))`.
> 이 PC 기준값: `naLwtcFb6GHceqWR4mHXuWi7LDA=` (친구 PC에서 빌드하면 값이 달라 별도 등록 필요).
