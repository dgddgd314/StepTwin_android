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
지도에 그릴 데이터를 받는다. 요청 바디(잠정)는 최신 보행 취약도:

```json
{ "speedWeight": 0.72, "turnWeight": 0.41, "strengthWeight": 0.83 }
```

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

## 6. 점선 패턴에 대한 메모

카카오 SDK v2 의 점선은 `RouteLinePattern` (반복 이미지) 로 처리한다.
현재 `res/drawable/route_dash_pattern.xml` (초록 대시 타일)을 사용하며 `custom_walk` 에 적용된다.
색을 서버 값에 100% 맞춰야 하면 타일 색을 코드에서 동적으로 만들도록 확장하면 된다.
`custom_walk` 도 기본은 초록 실선이 먼저 깔리므로, 패턴이 어떻게 보이든 선은 항상 보인다.
