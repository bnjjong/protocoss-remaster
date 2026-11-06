#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 1단계 리소스 일괄 변환 파이프라인 마스터 스크립트
"""
import os
import subprocess
import sys

def run_step(step_name, script_path):
    print(f"\n==========================================")
    print(f"▶ [실행] {step_name}")
    print(f"==========================================")
    python_bin = sys.executable
    res = subprocess.run([python_bin, script_path])
    if res.returncode != 0:
        print(f"❌ {step_name} 실패 (코드: {res.returncode})")
        return False
    return True

def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    
    steps = [
        ("전투 배경 일러스트 추출 (BACK.DAT)", os.path.join(base_dir, "decode_backgrounds.py")),
        ("스토리 컷씬 및 비주얼 전수 디코딩 (*.VIS, 227컷)", os.path.join(base_dir, "decode_vis.py")),
        ("효과음 16비트 WAV 일괄 변환 (*.EFF, 34종)", os.path.join(base_dir, "decode_sfx.py")),
        ("RPG 수치 데이터베이스 JSON 파싱 (*.LST, 4종)", os.path.join(base_dir, "decode_db.py")),
        ("대사 및 이벤트 스크립트 복호화 및 UTF-8 JSON 변환 (AREA*.G&M)", os.path.join(base_dir, "decode_script.py")),
        ("BGM 시퀀스 트랙 메타데이터 파싱 (*.MFA, 37곡)", os.path.join(base_dir, "decode_mfa.py")),
    ]

    for name, script in steps:
        if not run_step(name, script):
            sys.exit(1)

    print("\n🎉 [1단계 에셋 변환 파이프라인 전수 완료]")

if __name__ == "__main__":
    main()
