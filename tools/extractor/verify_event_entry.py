#!/usr/bin/env python3
"""원작 이벤트 실행기의 분기만 실행해 전 지역 이벤트 진입 주소를 대조한다."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from event_archive import EventArchive
from original_field import OriginalFieldMachine
from verify_original_map import unpack_game, EXPECTED_HASHES
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_ESP, UC_X86_REG_EAX


class NativeEventEntry:
    def __init__(self, executable):self.machine=OriginalFieldMachine(executable)

    def load(self, raw):
        count=struct.unpack_from('<H',raw,4)[0];m=self.machine
        m.cpu.mem_write(0x230000,raw[6:6+count*2])
        m.cpu.mem_write(0x240000,bytes(v^127 for v in raw[6+count*2:]))
        m.dword(0x15ac,0x230000);m.dword(0x15a8,0x240000)

    def resolve(self, group, argument, prefix):
        m=self.machine;c=m.cpu;result=[]
        m.cpu.mem_write(0x7e2a0,bytes(600));m.word(0xe2a0,group)
        def hook(cpu,address,size,user):
            if address!=0x25361:return
            pc=struct.unpack('<I',cpu.mem_read(0x715a4,4))[0]-0x240000
            if pc not in prefix:result.append(pc);cpu.emu_stop()
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:
            c.mem_write(0x300000,struct.pack('<I',0x3f0000))
            c.reg_write(UC_X86_REG_ESP,0x300000);c.reg_write(UC_X86_REG_EAX,argument)
            c.emu_start(0x25304,0x3f0000,count=200000)
        finally:c.hook_del(token)
        if len(result)!=1 or m.word(0xe2a0)!=group or m.word(0xe2a2)!=argument:
            raise ValueError('원작 이벤트 진입 기록에 실패했습니다.')
        return result[0]


def verify(game_dir, output_root, executable=None):
    native=NativeEventEntry(executable if executable is not None else unpack_game(game_dir))
    cases=[];sources={}
    for area in range(6):
        name=f'DATA/AREA{area:02}.G&M';raw=(game_dir/name).read_bytes();archive=EventArchive(raw)
        sources[name]=hashlib.sha256(raw).hexdigest();native.load(raw)
        for number,group in enumerate(archive.groups):
            prefix=set(range(group.offset,group.fallback_instruction,7))|{group.fallback_instruction}
            arguments={value for value,_ in group.routes}
            missing=next(value for value in range(65536) if value not in arguments)
            for argument in sorted(arguments|{missing,65535}):
                actual=native.resolve(number,argument,prefix);expected=archive.resolve(number,argument)
                if actual!=expected:raise ValueError(f'원작 이벤트 주소 불일치: {area,number,argument,actual,expected}')
                cases.append([area,number,argument,actual,archive.code[actual]])
    result=dict(function='0x25304',fetch='0x25361',condition='0x25A34',executable_sources=EXPECTED_HASHES,sources=sources,cases=cases)
    path=output_root/'docs/restoration/event-entry-native.json';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    print('원작 이벤트 진입 주소 일치:',len(cases),'개',flush=True)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify(a.game_dir,a.output_root)
