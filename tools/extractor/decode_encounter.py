#!/usr/bin/env python3
"""검은 망토 조우 VIS의 누적 프레임을 복원하고 원작 디코더와 대조한다."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
from PIL import Image
from original_field import OriginalFieldMachine
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ESP,UC_X86_REG_EIP


def decode_vis(raw):
    if len(raw)<775 or raw[4:7]!=b'VIS':raise ValueError('VIS 헤더 오류')
    groups,terminal=struct.unpack_from('<HH',raw)
    if not 0<groups<=256 or not 1<terminal<=10000:raise ValueError('VIS 프레임 수 오류')
    palette=raw[7:775]
    if any(v>63 for v in palette):raise ValueError('VIS 팔레트 오류')
    table_end=775+groups*10
    if table_end>len(raw):raise ValueError('VIS 표 잘림')
    offsets=struct.unpack_from(f'<{groups}I',raw,775+groups*6)
    parts={}
    for index,offset in enumerate(offsets):
        if offset<table_end or offset+6>len(raw):raise ValueError('VIS 구간 주소 오류')
        first,count,length=struct.unpack_from('<3H',raw,offset)
        if (first,count,length)!=struct.unpack_from('<3H',raw,775+index*6):raise ValueError('VIS 구간 표 불일치')
        sizes=struct.unpack_from(f'<{count}H',raw,offset+6)
        position=offset+6+count*2
        if sum(sizes)!=length or position+length>len(raw):raise ValueError('VIS 구간 길이 오류')
        for i,size in enumerate(sizes):
            if first+i in parts:raise ValueError('VIS 프레임 중복')
            parts[first+i]=raw[position:position+size];position+=size
    screen=bytearray(64000);frames=[]
    for frame in range(terminal-1):
        payload=parts[frame]
        if payload:
            # 네 바이트의 보조 헤더 뒤에서 화면 시작을 기준으로 차이만 기록한다.
            position=4;pixel=0
            while True:
                if position>=len(payload):raise ValueError('VIS 종료 명령 누락')
                op=payload[position];position+=1
                if op==128:
                    if position+2>len(payload):raise ValueError('VIS 긴 명령 잘림')
                    value=struct.unpack_from('<H',payload,position)[0];position+=2
                    if value==0:break
                    count=value&16383
                    if value&32768:kind='fill' if value&16384 else 'copy'
                    else:kind='skip';count=value
                elif op==0:
                    if position>=len(payload):raise ValueError('VIS 반복 길이 누락')
                    count=payload[position];position+=1;kind='fill'
                else:count=op&127;kind='skip' if op&128 else 'copy'
                if pixel+count>64000:raise ValueError('VIS 화면 범위 초과')
                if kind=='fill':
                    if position>=len(payload):raise ValueError('VIS 반복 색 누락')
                    screen[pixel:pixel+count]=bytes([payload[position]])*count;position+=1
                elif kind=='copy':
                    if position+count>len(payload):raise ValueError('VIS 픽셀 잘림')
                    screen[pixel:pixel+count]=payload[position:position+count];position+=count
                pixel+=count
            if position!=len(payload):raise ValueError('VIS 프레임 뒤 미해석 데이터')
        frames.append(bytes(screen))
    return palette,frames


class OriginalVisDecoder(OriginalFieldMachine):
    def decode(self,raw):
        position=0;heap=0x100000;c=self.cpu
        def hook(cpu,address,size,user):
            nonlocal position,heap
            if address not in (0x6788a,0x67163,0x671f9,0x67a89,0x682c0,0x66f6e):return
            result=0
            if address==0x6788a:result=heap;heap+=(cpu.reg_read(UC_X86_REG_EAX)+15)//16*16
            elif address==0x67163:position=0;result=0x2e0000
            elif address==0x671f9:
                length=cpu.reg_read(UC_X86_REG_EDX)*cpu.reg_read(UC_X86_REG_EBX)
                chunk=raw[position:position+length]
                if len(chunk)!=length:raise ValueError('원작 VIS 읽기 범위 초과')
                cpu.mem_write(cpu.reg_read(UC_X86_REG_EAX),chunk);position+=length;result=cpu.reg_read(UC_X86_REG_EBX)
            elif address==0x67a89:
                if cpu.reg_read(UC_X86_REG_EBX)!=0:raise ValueError('미지원 원작 VIS 탐색')
                position=cpu.reg_read(UC_X86_REG_EDX)
            elif address==0x682c0:
                length=cpu.reg_read(UC_X86_REG_EBX)
                if cpu.mem_read(cpu.reg_read(UC_X86_REG_EAX),length)!=cpu.mem_read(cpu.reg_read(UC_X86_REG_EDX),length):result=1
            sp=cpu.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',cpu.mem_read(sp,4))[0]
            cpu.reg_write(UC_X86_REG_EAX,result);cpu.reg_write(UC_X86_REG_ESP,sp+4);cpu.reg_write(UC_X86_REG_EIP,ret)
        token=c.hook_add(UC_HOOK_CODE,hook)
        def call(address,eax=0):
            c.mem_write(0x300000,struct.pack('<I',0x3f0000));c.reg_write(UC_X86_REG_ESP,0x300000);c.reg_write(UC_X86_REG_EAX,eax)
            c.emu_start(address,0x3f0000,count=999999)
            if c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError('원작 VIS 디코더 종료 실패')
        try:
            call(0x2c544);c.mem_write(0x2f0000,b'#0.vis\0');call(0x2c5a4,0x2f0000)
            frames=[]
            for _ in range(struct.unpack_from('<H',raw,2)[0]-1):
                call(0x2c50c,0x280000);frames.append(bytes(c.mem_read(0x280000,64000)))
            return frames
        finally:c.hook_del(token)


def extract(game_dir,output_root,executable=None):
    from verify_original_map import unpack_game
    raw=(game_dir/'GRP/#0.VIS').read_bytes();palette,frames=decode_vis(raw)
    native=OriginalVisDecoder(executable if executable is not None else unpack_game(game_dir)).decode(raw)
    if frames!=native:raise ValueError('조우 영상이 원작 디코더 결과와 다릅니다.')
    output=output_root/'assets/encounter';output.mkdir(parents=True,exist_ok=True)
    # 0x2C207은 INT 1Ch를 등록한다. 영상 3 BIOS 틱과 팔레트 감쇠 1 VBL을 구분한다.
    stream=[(3*65536/1193182,palette+pixels) for pixels in frames]
    for level in range(64,-1,-1):stream.append((1/70,bytes(v*level//64 for v in palette)+frames[-1]))
    data=bytearray(struct.pack('>4sHHI',b'PV01',320,200,len(stream)));previous=bytes(64768)
    for seconds,frame in stream:
        data.extend(struct.pack('>d',seconds));data.extend(a^b for a,b in zip(previous,frame));previous=frame
    (output/'encounter.pvid').write_bytes(gzip.compress(data,mtime=0))
    for index in (0,12,24):
        im=Image.frombytes('P',(320,200),frames[index]);im.putpalette([v*4 for v in palette]);im.save(output/f'reference-{index}.png')
    manifest=dict(source_sha256=hashlib.sha256(raw).hexdigest(),frames=25,compared_pixels=25*64000,
        duration_seconds=sum(t for t,_ in stream),frame_sha256=[hashlib.sha256(f).hexdigest() for f in frames],
        frame_state_sha256=[hashlib.sha256(palette+f).hexdigest() for f in frames],
        timer_interrupt="INT 1Ch",frame_ticks=3,tick_seconds=65536/1193182)
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('검은 망토 영상: 원작 25프레임·1,600,000픽셀 일치')
    return manifest


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--game-dir',required=True,type=Path)
    parser.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);args=parser.parse_args()
    extract(args.game_dir,args.output_root)
