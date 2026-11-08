#!/usr/bin/env python3
"""검 수리·엔키드 합류·쪽지의 원작 상태 변경과 대화를 실행한다."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import struct
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX,UC_X86_REG_ESP,UC_X86_REG_EIP
from original_field import OriginalFieldMachine
from verify_original_map import unpack_game


class StoryReference(OriginalFieldMachine):
    def load_save(self, source):
        self.source=bytes(source);self.regions=[];position=0
        def hook(c,address,size,user):
            nonlocal position
            if address not in (0x67163,0x671f9,0x67c9c,0x673c3,0x66368):return
            if address==0x67163:c.reg_write(UC_X86_REG_EAX,0x200000)
            elif address==0x671f9:
                destination=c.reg_read(UC_X86_REG_EAX);length=c.reg_read(UC_X86_REG_EDX)*c.reg_read(UC_X86_REG_EBX)
                chunk=source[position:position+length]
                if len(chunk)!=length:raise ValueError('원작 저장 읽기 범위 오류')
                c.mem_write(destination,bytes(chunk));self.regions.append((position,length,destination));position+=length
                c.reg_write(UC_X86_REG_EAX,1)
            elif address==0x67c9c:
                c.mem_write(c.reg_read(UC_X86_REG_EBX),struct.pack('<I',struct.unpack_from('<H',source,2942)[0]^0x7f7f))
                c.mem_write(c.reg_read(UC_X86_REG_EDX),bytes(4))
            self.return_from(c)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x1bb74,0xffffffff)
        finally:self.cpu.hook_del(token)
        if position!=2944:raise ValueError('원작 저장 읽기가 끝나지 않았습니다.')

    def saved(self):
        result=bytearray(self.source)
        for offset,length,destination in self.regions[:-1]:result[offset:offset+length]=self.cpu.mem_read(destination,length)
        return bytes(result)

    @staticmethod
    def return_from(c,cleanup=0):
        sp=c.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',c.mem_read(sp,4))[0]
        c.reg_write(UC_X86_REG_ESP,sp+4+cleanup);c.reg_write(UC_X86_REG_EIP,ret)

    def event(self, game_dir, group, argument):
        raw=(game_dir/'DATA/AREA00.G&M').read_bytes();count=struct.unpack_from('<H',raw,4)[0]
        self.cpu.mem_write(0x230000,raw[6:6+count*2]);self.cpu.mem_write(0x240000,bytes(b^127 for b in raw[6+count*2:]))
        self.dword(0x15ac,0x230000);self.dword(0x15a8,0x240000);self.word(0x15b0,0);self.word(0xe50c,count);self.word(0xe2a0,group)
        self.cpu.mem_write(0x280000,(game_dir/'DATA/WEAPON.LST').read_bytes());self.dword(0x1508,0x280000)
        self.dword(0x1598,0x290000);self.dword(0x159c,0x291000)
        events=[]
        def hook(c,address,size,user):
            args=[c.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
            if address==0x67b85:
                c.reg_write(UC_X86_REG_EAX,bytes(c.mem_read(args[0],8192)).index(0));self.return_from(c)
            elif address==0x679da:self.return_from(c)
            elif address==0x1f8b0:
                events.append(dict(kind='dialogue',text=bytes(c.mem_read(args[0],8192)).split(b'\0')[0].decode('cp1361')));self.return_from(c)
            elif address in (0x133e0,0x132a4,0x284b8):self.return_from(c)
            elif address==0x12924:events.append(dict(kind='item',id=args[0],count=args[1],category=args[2]))
            elif address==0x12aa4:
                # 소지품 변경 이후다. 화면 표시와 키 대기는 별도 화면 검사에서 확인한다.
                c.reg_write(UC_X86_REG_EIP,0x12dbb)
            elif address in (0x1255c,0x12f4c):events.append(dict(kind='party_add' if address==0x1255c else 'party_remove',id=args[0]))
            elif address==0x10a54:
                events.append(dict(kind='animation',id=args[0],mode=args[1],music=args[2]))
                # AUTOANI의 마지막 대화 호출은 별도로 원작 함수에서 검사한다.
                self.word(0xe2a2,{5:17,6:7}.get(args[0],self.word(0xe2a2)));self.return_from(c)
            elif address==0x29a1c:events.append(dict(kind='shop',args=args[:2]));self.return_from(c)
            elif address==0x1ee50:
                sp=c.reg_read(UC_X86_REG_ESP);events.append(dict(kind='warp',args=args+list(struct.unpack('<4I',c.mem_read(sp+4,16)))));self.return_from(c,16)
            elif address in (0x2b6f0,0x11cdc):
                events.append(dict(kind='effect' if address==0x2b6f0 else 'door',id=args[0]));self.return_from(c)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25304,argument)
        finally:self.cpu.hook_del(token)
        return events


def verify(game_dir, output_root, executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    start=(game_dir/'DATA/START.DAT').read_bytes();cases=[]
    for group,argument in [(4,2),(3,15),(1,1)]:
        for stage in range(6):
            for variant in range(3):
                source=bytearray(start)
                struct.pack_into('<I',source,96,{4:5,3:4,1:0}[group])
                struct.pack_into('<H',source,2342,group);struct.pack_into('<H',source,2344,77)
                struct.pack_into('<H',source,2350,{4:10,3:24,1:25}[group])
                struct.pack_into('<H',source,2342+8*2,stage);struct.pack_into('<H',source,2342+99*2,123)
                source[162]=(0,98,255)[variant]
                # 합류 전·이탈 전의 유효한 파티 상태.
                if group==1 and stage==2:source[500]=1
                if variant:
                    source[473]=3
                    struct.pack_into('<HHHH',source,505,3,45,2,50)
                    source[540]=variant;source[612+variant]=17;source[600+variant]=2
                    source[630]=163;source[631]=174;source[325]=9;source[336]=20
                machine=StoryReference(exe);machine.load_save(source)
                events=machine.event(game_dir,group,argument);after=machine.saved()
                cases.append(dict(group=group,argument=argument,stage=stage,variant=variant,
                    before=base64.b64encode(source).decode(),after=base64.b64encode(after).decode(),events=events))
    report=dict(source_sha256=hashlib.sha256((game_dir/'DATA/AREA00.G&M').read_bytes()).hexdigest(),cases=cases)
    (output_root/'docs/sword-native-verification.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('검 수리 원작 상태 검사:',len(cases),'개')
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify(a.game_dir,a.output_root)
