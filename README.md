# Protocoss Remaster (프로토코스: 신들의 예언서 2D 리마스터)

1995년작 고전 국산 DOS RPG 《프로토코스: 신들의 예언서 (Team AMOS)》를 최신 macOS(Apple Silicon/Intel) 환경에서 네이티브로 실행되는 고품질 2D 리마스터 게임으로 재구현하는 프로젝트입니다.

## 1. 프로젝트 구조

```
protocoss-remaster/
├── assets/                          # 추출 및 정규화된 현대적 에셋
│   ├── backgrounds/                 # 전투 배경 32장 무손실 PNG
│   ├── cutscenes/                   # 스토리 컷씬/비주얼 227컷 PNG
│   ├── audio/
│   │   ├── sfx/                     # 16비트 표준 무손실 WAV 효과음 34종
│   │   └── bgm/                     # BGM 시퀀스 트랙 데이터 37곡
│   └── data/
│       ├── db/                      # 수치 DB JSON (아이템, 마법, 몬스터, 장비)
│       └── scripts/                 # 복호화된 대사/이벤트 스크립트 UTF-8 JSON
├── tools/
│   └── extractor/                   # 1단계 에셋 전수 추출 파이프라인 (Python)
│       ├── extract_all.py           # 원클릭 마스터 실행 스크립트
│       ├── decode_backgrounds.py    # BACK.DAT 전투 배경 디코더
│       ├── decode_vis.py            # GRP/*.VIS 컷씬 RLE 디코더
│       ├── decode_sfx.py            # S&W/*.EFF 원시 PCM -> 16bit WAV 변환기
│       ├── decode_db.py             # DATA/*.LST 바이너리 C Struct 언팩기
│       ├── decode_script.py         # AREA*.G&M 0x7F XOR + 조합형 한글 -> UTF-8 변환기
│       └── decode_mfa.py            # S&W/*.MFA BGM 시퀀서 트랙 파서
├── core/                            # Kotlin + LibGDX 코어 모듈
└── desktop/                         # macOS 데스크톱 런처 (LWJGL3 / Apple Silicon)
```

## 2. 1단계 에셋 파이프라인 실행 방법

```bash
# 가상환경 활성화 및 필수 패키지 설치
source .venv/bin/activate
pip install Pillow mido capstone

# 전체 리소스 일괄 변환 파이프라인 실행
python tools/extractor/extract_all.py
```
