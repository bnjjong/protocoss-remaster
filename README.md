# Protocoss Remaster (프로토코스: 신들의 예언서 2D 리마스터)

1995년작 고전 국산 DOS RPG 《프로토코스: 신들의 예언서 (Team AMOS)》를 최신 macOS(Apple Silicon/Intel) 환경에서 네이티브로 실행되는 고품질 2D 리마스터 게임으로 재구현하는 프로젝트입니다.

현재는 원작 데이터와 산출물을 하나씩 대조하며 복원하는 단계입니다. **현재 검증한 범위는 USER.SPR 11개 캐릭터·599프레임, 오두막 배경·보행·문 왕복, 시작 화면·새 게임 프롤로그·음악, 검은 망토 조우와 마을 진입, 엘가임 주민 이동·대화와 건물 출입·실내 이동, 검 수리·엔키드 합류·쪽지, 소지품 조회·파티원별 여섯 슬롯 장착·자동 장비·자동 해제, DOS 저장 읽기·쓰기입니다.** 엘가임 맵 4·12의 주민과 실내 맵 5~11을 연결했습니다. 잡화점에서 검 맡기기 → 엔키드 합류 → 오두막 쪽지 → 단검 회수까지 연결했습니다. 현재 참가 중인 파티원의 투구·갑옷·무기·방패·장신구를 바꾸고 저장 후 이어갈 수 있습니다. 자동 장비·해제는 원작처럼 장신구를 유지하며 네 슬롯만 바꿉니다. 일반 거래·다른 실내 퀘스트·다른 지역의 NPC·전투는 후속 작업입니다.

[USER.SPR 복원 근거와 재현 방법](docs/sprite-format.md) · [첫 오두막 맵 분석](docs/map-format.md) · [필드 경계 수정·실행 검사](docs/field-bounds.md) · [시작 화면·DOS 저장](docs/title-save.md) · [프롤로그 복원](docs/prologue.md) · [오두막 시작 연출·비교 화면](docs/opening.md) · [보행·문 왕복·기상 연출](docs/field-travel.md) · [마을 진입·검은 망토 조우](docs/town-travel.md) · [엘가임 주민 이동·대화와 비교 화면](docs/town-npcs.md) · [마을 건물 출입·실내 비교](docs/town-buildings.md) · [검 수리·합류·쪽지와 비교 화면](docs/sword-story.md) · [카만 무기 메뉴 1차 기록](docs/equipment.md) · [파티원별 장비 메뉴](docs/party-equipment.md) · [자동 장비·자동 해제](docs/automatic-equipment.md)

실행은 `./gradlew :desktop:run`입니다. 원작 타이틀에서 `NEW START`를 선택하면 프롤로그와 음악이 재생됩니다. 역사 프롤로그는 Esc·Enter·Space로 건너뛸 수 있으며, 이후 오두막 외부·실내 연출과 대화가 이어집니다. 엘가임 주민 앞에서 Space·Enter·E로 말을 걸 수 있습니다. 대화는 Space·Enter로 진행하며, 주민 대화에서는 E도 사용할 수 있습니다. `CONTINUE`는 저장된 필드로 들어갑니다. 필드와 실내에서 Esc로 저장 메뉴를 엽니다. 마을 문 앞에서 이동하면 입구를 통과하며, 죠엘의 집은 초반에 잠겨 있습니다. 잡화점 주인에게 검을 맡긴 뒤 가게 앞에서 엔키드를 만나고, 오두막으로 돌아가 쪽지를 확인한 다음 잡화점에서 단검을 받습니다. 단검은 소지품으로 들어가며 자동 장착하지 않습니다. Esc 메뉴의 `소지품`에서 이름·그림·수량을 조회하고, `장비`에서 위·아래로 참가 파티원을 고르고, 확인 후 방향키로 여섯 슬롯을 고릅니다. 장비 선택 목록을 열면 현재 슬롯을 먼저 해제하며 취소해도 해제 상태를 유지합니다. 처음 장비 화면에서 좌우로 자동 장비·자동 해제 탭을 고르고 Enter·Space로 실행할 수 있습니다. 일반 거래와 다른 실내 퀘스트는 아직 연결하지 않았습니다.

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
