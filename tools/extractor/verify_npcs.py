#!/usr/bin/env python3
"""원작 주민 이동·충돌·대화 대상을 실행해 Kotlin 대조 자료를 만든다."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from original_field import OriginalFieldMachine
from verify_original_map import unpack_game, read_offsets
from unicorn.x86_const import UC_X86_REG_EAX


def snapshot(machine, count):
    result=[]
    for i in range(count):
        v=struct.unpack('<12I6B',machine.cpu.mem_read(0x220000+i*54,54))
        result.append([v[1],v[2],v[5],v[6],v[12],v[13],v[16]])
    return result


def verify(game_dir, output_root, executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    offsets=read_offsets(game_dir/'MAP/AREA00.MDA');raw=(game_dir/'MAP/AREA00.MAP').read_bytes()
    motion=[];collision=[];targets=[];approaches=[];dialogues=[]
    for number in (4,12):
        source=raw[offsets[number]:offsets[number+1]]
        for hero in ((72,8),(824,464)):
            m=OriginalFieldMachine(exe);m.load_map(source);m.dword(0x7264,1)
            m.dword(0xd7fc,0);m.dword(0xd800,0);m.dword(0xe1e4,hero[0]+16);m.dword(0xe1e8,hero[1]+16)
            phase=0.0;bios=0;frames=[];digest=hashlib.sha256()
            for tick in range(1,1401):
                phase+=1193182/(65536*70)
                while phase>=1:
                    phase-=1;bios+=1
                    if bios%4==0:m.word(0xe1de,1)
                    if bios%60==0:m.word(0xe1e2,1)
                m.call(0x23da4)
                state=snapshot(m,source[34])
                digest.update(b''.join(struct.pack('<7i',*a) for a in state))
                if tick<=32 or tick%70==0:frames.append(dict(tick=tick,actors=state))
            motion.append(dict(map=number,hero=list(hero),ticks=1400,sha256=digest.hexdigest(),checkpoints=frames))
        m=OriginalFieldMachine(exe);m.load_map(source)
        for index,actor in enumerate(snapshot(m,source[34])):
            m=OriginalFieldMachine(exe);m.load_map(source)
            nx,ny=actor[2:4]
            # 양방향 충돌의 경계·높이 층을 포함한 주민 주변 표본.
            for dx in range(-7,8):
                for dy in range(-5,6):
                    x=(nx+dx)*8+3;y=(ny+dy)*8+5
                    for direction in (1,2,4,8):
                        m.dword(0xd7fc,0);m.dword(0xd800,0);m.dword(0xe1e4,x+16);m.dword(0xe1e8,y+16)
                        m.byte(0xe1f8,direction);m.byte(0xe1fc,8)
                        # 실행 중 지형 분기 전, 주민 충돌 루프만 직접 실행한다.
                        c=m.cpu;c.mem_write(0x300000,struct.pack('<I',0x3f0000))
                        from unicorn.x86_const import UC_X86_REG_ESP,UC_X86_REG_EIP
                        c.reg_write(UC_X86_REG_ESP,0x300000)
                        c.emu_start(0x1fd84,0x1ff8c,count=200000)
                        if c.reg_read(UC_X86_REG_EIP)!=0x1ff8c:raise ValueError('주민 충돌 루프 종료 실패')
                        collision.append([number,x,y,direction,m.byte(0xe1f8)==0])
                    if dx%2==0 and dy%2==0:
                        for pose in (0,4,8,12):
                            for layer in (0,1):
                                m.byte(0xe1fc,pose);m.byte(0xe1fb,layer);m.call(0x21464)
                                value=m.cpu.reg_read(UC_X86_REG_EAX)
                                targets.append([number,x,y,pose,layer,value if value<0x80000000 else -1])
            # 움직이는 인물을 네 방향에서 부르면 0x23804가 실제로 이동시키는 경로.
            for pose,(dx,dy) in enumerate(((0,16),(-32,0),(0,-16),(32,0))):
                m=OriginalFieldMachine(exe);m.load_map(source)
                m.dword(0xd80c,0);m.dword(0xd810,0);m.dword(0xe52c,0);m.dword(0xe530,0);m.dword(0xe548,0)
                hx=actor[0]+dx;hy=actor[1]+dy
                m.dword(0xe1e4,hx+16);m.dword(0xe1e8,hy+16);m.byte(0xe1fc,pose*4)
                m.cpu.mem_write(0x220000+index*54+52,b'\1');m.word(0xe1de,1)
                states=[];phase=0.0;bios=0
                for tick in range(100):
                    phase+=1193182/(65536*70)
                    while phase>=1:
                        phase-=1;bios+=1
                        if bios%4==0:m.word(0xe1de,1)
                    m.call(0x23804,index);ready=m.cpu.reg_read(UC_X86_REG_EAX)
                    m.call(0x23da4);states.append(snapshot(m,source[34])[index])
                    if ready:break
                else:raise ValueError('주민 대화 접근 종료 실패')
                approaches.append(dict(map=number,index=index,hero=[hx,hy],pose=pose*4,states=states))
        print('원작 주민 검사:',number,flush=True)
    gm=(game_dir/'DATA/AREA00.G&M').read_bytes()
    for event in (10,11,12,13,14,18,19):
        for stage in range(6):
            result=OriginalFieldMachine(exe).trace_event(gm,3,event,{8:stage,99:123},capture_dialogue=True)
            dialogues.append(dict(event=event,stage=stage,result=result))
    report=dict(sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('MAP/AREA00.MAP','DATA/AREA00.G&M')},
        motion=motion,collision=collision,targets=targets,approaches=approaches,dialogues=dialogues)
    (output_root/'docs/npc-native-verification.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('원작 주민 기록 완료:',len(collision),'충돌,',len(targets),'대화 대상,',len(dialogues),'대사 분기',flush=True)
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify(a.game_dir,a.output_root)
