#!/usr/bin/env python3
"""원작 0x243F0의 정렬 구간을 실행해 인물 순서 대조 자료를 만든다."""
import argparse
import itertools
import json
from pathlib import Path
import random
import struct
from original_field import OriginalFieldMachine
from verify_original_map import unpack_game
from unicorn.x86_const import UC_X86_REG_ESP, UC_X86_REG_EIP


class NativeFieldOrder:
    def __init__(self, executable):
        self.machine=OriginalFieldMachine(executable)

    def indices(self, y, layers=None, scroll=0):
        """입력 첫 항목은 카만이다. 화면 밖 인물도 원작 목록에 포함한다."""
        if not 1<=len(y)<=51:raise ValueError('인물 수가 분석한 목록 범위를 벗어났습니다.')
        if layers is None:layers=[0]*len(y)
        if len(layers)!=len(y):raise ValueError('인물 수와 높이 층 수가 다릅니다.')
        m=self.machine;c=m.cpu
        m.word(0xe1e0,len(y)-1);m.dword(0x157c,0x220000)
        m.dword(0xe1e8,y[0]-scroll+16);m.byte(0xe1fb,layers[0])
        m.dword(0xd810,scroll//8);m.dword(0xe530,scroll%8)
        for i,world_y in enumerate(y[1:]):
            row=bytearray(54);struct.pack_into('<i',row,8,world_y);row[53]=layers[i+1]
            c.mem_write(0x220000+i*54,bytes(row))
        c.reg_write(UC_X86_REG_ESP,0x300000)
        # 정렬을 끝낸 직후, 실제 스프라이트 출력으로 들어가기 전에 멈춘다.
        c.emu_start(0x243f0,0x24530,count=200000)
        if c.reg_read(UC_X86_REG_EIP)!=0x24530:raise ValueError('원작 인물 정렬이 끝나지 않았습니다.')
        raw=bytes(c.mem_read(0x7e08c,len(y)*6))
        rows=[struct.unpack_from('<iBB',raw,i*6) for i in range(len(y))]
        order=[row[2] for row in rows]
        if sorted(order)!=list(range(len(y))):raise ValueError('원작 인물 목록이 손상됐습니다.')
        if any(row[:2]!=(y[index]-scroll+16,layers[index]) for row,index in zip(rows,order)):
            raise ValueError('원작 인물 좌표·높이 층 기록이 다릅니다.')
        return order

    def actors(self, actors, scroll_y):
        """실행 검사 기록은 NPC 다음에 카만을 저장한다. 원작 입력 순서로 바꾼다."""
        rows=[actors[-1],*actors[:-1]]
        order=self.indices([row[2] for row in rows],[row[4] for row in rows],scroll_y)
        return [rows[i] for i in order]


def extract(game_dir, output_root, executable=None):
    native=NativeFieldOrder(executable if executable is not None else unpack_game(game_dir))
    cases=[];rng=random.Random(37)
    positions=[[0],*[list(p) for size in range(2,6) for p in itertools.product((-8,0,8),repeat=size)]]
    positions += [[rng.randrange(-32,601) for _ in range(rng.choice((2,3,6,12,24,51)))] for _ in range(200)]
    for y in positions:
        layers=[rng.randrange(3) for _ in y];scroll=rng.randrange(0,401)
        cases.append(dict(y=y,layers=layers,scroll=scroll,order=native.indices(y,layers,scroll)))
    result=dict(function='0x243F0',stop='0x24530',cases=cases)
    path=output_root/'docs/field-draw-order-native.json'
    path.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    print('원작 인물 정렬 대조 자료:',len(cases),'개',flush=True)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    extract(a.game_dir,a.output_root)
