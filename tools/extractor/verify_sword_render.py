#!/usr/bin/env python3
"""검 수리 대화·획득 알림·합류·쪽지 화면을 원작 함수로 대조한다."""
import argparse
from pathlib import Path
from verify_npc_render import verify


def verify_sword(game_dir, output_root, executable=None):
    return verify(game_dir,output_root,executable,output_root/'desktop/build/reports/sword','sword-render-verification.json')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify_sword(a.game_dir,a.output_root)
