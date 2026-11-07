# Protocoss Remaster (프로토코스: 신들의 예언서 2D 리마스터)

1995년작 고전 국산 DOS RPG 《프로토코스: 신들의 예언서 (Team AMOS)》를 최신 macOS(Apple Silicon/Intel) 환경에서 네이티브로 실행되는 고품질 2D 리마스터 게임으로 재구현하는 프로젝트입니다.

현재는 원작 데이터와 산출물을 하나씩 대조하며 복원하는 단계입니다. **현재 검증한 범위는 USER.SPR 11개 캐릭터·599프레임, 첫 오두막 정적 배경, 원작 시작 화면과 DOS 저장 파일 읽기·쓰기 및 필드 진입입니다.** 컷씬, NPC·다른 스프라이트·나머지 UI, 음원 재생, 이벤트·전투는 후속 작업입니다. DOS 저장의 파티·이벤트 상태는 보존하지만 해당 게임 시스템과의 연결은 아직 남아 있습니다.

[USER.SPR 복원 근거와 재현 방법](docs/sprite-format.md) · [첫 오두막 맵 분석](docs/map-format.md) · [필드 경계 수정·실행 검사](docs/field-bounds.md) · [시작 화면·DOS 저장](docs/title-save.md)

실행은 `./gradlew :desktop:run`입니다. 원작 타이틀에서 `NEW START` 또는 `CONTINUE`를 선택하고, 필드에서 Esc로 저장 메뉴를 엽니다.

## 1. 프로젝트 구조

```
protocoss-remaster/
├── assets/                          # 추출 및 정규화된 현대적 에셋
│   ├── sprites/user/                 # 원작과 비교한 11개 캐릭터·599프레임
│   ├── backgrounds/                 # 전투 배경 32장 무손실 PNG
│   ├── cutscenes/                   # 기존 VIS 산출물 (복원 오류 확인, 재작업 대상)
│   ├── audio/
│   │   ├── sfx/                     # 16비트 표준 무손실 WAV 효과음 34종
│   │   └── bgm/                     # BGM 시퀀스 트랙 데이터 37곡
│   └── data/
│       ├── db/                      # 수치 DB JSON (아이템, 마법, 몬스터, 장비)
│       └── scripts/                 # 복호화된 대사/이벤트 스크립트 UTF-8 JSON
├── tools/
│   └── extractor/                   # 형식별 추출·검증 도구 (Python)
│       ├── decode_user_sprite.py    # USER.SPR 전체 또는 선택 캐릭터 추출
│       ├── verify_original_user_sprite.py # 원작 함수와 픽셀 비교
│       ├── extract_all.py           # 기존 일괄 스크립트 (전체 복원 미포함)
│       ├── decode_backgrounds.py    # BACK.DAT 전투 배경 디코더
│       ├── decode_vis.py            # 기존 VIS 추출기 (재작업 대상)
│       ├── decode_sfx.py            # S&W/*.EFF 원시 PCM -> 16bit WAV 변환기
│       ├── decode_db.py             # DATA/*.LST 바이너리 C Struct 언팩기
│       ├── decode_script.py         # AREA*.G&M 0x7F XOR + 조합형 한글 -> UTF-8 변환기
│       └── decode_mfa.py            # S&W/*.MFA BGM 시퀀서 트랙 파서
├── core/                            # Kotlin + LibGDX 코어 모듈
└── desktop/                         # macOS 데스크톱 런처 (LWJGL3 / Apple Silicon)
```

## 2. 현재 검증한 USER.SPR 추출 실행 방법

```bash
python3 -m venv .venv
.venv/bin/pip install Pillow unicorn
.venv/bin/python tools/extractor/decode_user_sprite.py \
  --game-dir /Users/df/Downloads/Protocoss/Protocoss_Game --all-characters
.venv/bin/python tools/extractor/verify_original_user_sprite.py \
  --game-dir /Users/df/Downloads/Protocoss/Protocoss_Game --all-characters
```

이 명령은 원본 11개 캐릭터의 RGBA PNG 599장과 색 번호·투명도 데이터, 현재 데모용 카만 첫 프레임을 생성합니다. 캐릭터를 지정하려면 `--all-characters` 대신 `--character 7`처럼 입력합니다. 모션과 장면별 팔레트를 게임에 연결하는 단계는 아직 진행하지 않았습니다.
