#!/usr/bin/env python3
"""실제 앱의 실내·열린 문·잠긴 문 대화를 원작 VGA 함수로 대조한다."""
import argparse
from pathlib import Path
from verify_npc_render import verify


def verify_buildings(game_dir, output_root, executable=None):
    return verify(game_dir,output_root,executable,output_root/'desktop/build/reports/buildings','buildings-render-verification.json')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify_buildings(a.game_dir,a.output_root)
