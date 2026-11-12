#!/usr/bin/env python3
"""원작의 대화·이동·장소명·효과음 명령과 저장 상태를 실제 함수로 대조한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import *
from sword_story import StoryReference
from event_archive import EventArchive
from verify_original_map import unpack_game,EXPECTED_HASHES
from decode_opening import dialogue

GROUP_MAP={0:2,1:0,2:3,3:4,4:5,5:8,6:9,7:10,8:11,9:6,10:7}
EVENTS={0:[1,4,5,6,7,9,10,15,16],1:[0,1,2],2:[0,1,2],3:list(range(0,16))+[18,19],
    4:[0,1,2],5:[0,1,2,3,4],6:[0],7:[0,1],8:[0,1],9:[0,1],10:[1,2,4]}


class FieldReference(StoryReference):
    def __init__(self,exe):
        super().__init__(exe);self.context=self.cpu.context_save()
    def event(self,raw,source,argument):
        c=self.cpu;c.context_restore(self.context);self.load_save(source);c.context_restore(self.context)
        archive=EventArchive(raw);count=len(archive.groups)
        c.mem_write(0x230000,raw[6:6+count*2]);c.mem_write(0x240000,archive.code)
        self.dword(0x15ac,0x230000);self.dword(0x15a8,0x240000);self.word(0xe50c,count);self.word(0x15b0,0)
        c.mem_write(0x3e0000,b'\xc3');self.dword(0xfd40,0x3e0000)
        self.dword(0x164c,0);self.byte(0x1660,0)
        requests=[];ending=[];steps=0;last=0
        def pc():return struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000
        def snapshot(kind,**extra):
            return dict(kind=kind,offset=pc(),at=last,instructions=steps,
                variables=hashlib.sha256(bytes(c.mem_read(0x7e2a0,600))).hexdigest(),**extra)
        def cstring(pointer):return bytes(c.mem_read(pointer,4096)).split(b'\0')[0]
        def unwind(registers):
            bp=c.reg_read(UC_X86_REG_EBP);values=struct.unpack(f'<{len(registers)+1}I',c.mem_read(bp,(len(registers)+1)*4))
            for reg,value in zip(registers,values):c.reg_write(reg,value)
            c.reg_write(UC_X86_REG_ESP,bp+(len(registers)+1)*4);c.reg_write(UC_X86_REG_EIP,values[-1])
        skip={0x1dc54:0,0x235e4:0,0x263a0:0,0x1f108:4,0x2ca5e:8,0x2cb5f:8,
            0x23da4:0,0x243f0:0,0x22148:0,0x2b838:0,0x66368:0,0x66025:0,0x66f6e:0,0x284b8:0}
        def hook(cpu,address,size,user):
            nonlocal steps,last
            if address==0x25361:
                last=pc();op=archive.code[last]
                if op not in set(range(0xf0,0xfb))|{2,3,0x15,0x17}:
                    ending.append(snapshot('unhandled',opcode=op));cpu.emu_stop();return
                steps+=1
            elif address==0x1f8b0:
                text=cstring(cpu.reg_read(UC_X86_REG_EAX))
                requests.append(snapshot('dialogue',raw=base64.b64encode(text).decode(),pages=dialogue(text)))
                self.return_from(cpu)
            elif address==0x1ee50:
                sp=cpu.reg_read(UC_X86_REG_ESP)
                args=[cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
                args+=list(struct.unpack('<4I',cpu.mem_read(sp+4,16)))
                requests.append(snapshot('warp',args=args))
            elif address==0x2b6f0:
                requests.append(snapshot('effect',id=cpu.reg_read(UC_X86_REG_EAX)));self.return_from(cpu)
            elif address==0x1eb30:
                requests.append(snapshot('place',text=cstring(cpu.reg_read(UC_X86_REG_EAX)).decode('cp1361')))
            elif address==0x1e1cd:cpu.reg_write(UC_X86_REG_EIP,0x1e213)
            elif address==0x1e21b:
                # 맵 번호를 기록한 직후 파일 읽기·타일/인물 자산 로드를 생략한다.
                unwind([UC_X86_REG_EBP,UC_X86_REG_EDI,UC_X86_REG_ESI,UC_X86_REG_EDX,UC_X86_REG_ECX,UC_X86_REG_EBX])
            elif address==0x1eb5e:
                # 장소명을 저장한 직후 표시·키 대기를 끝낸다.
                unwind([UC_X86_REG_EBP,UC_X86_REG_EDI,UC_X86_REG_ESI,UC_X86_REG_EDX,UC_X86_REG_ECX,UC_X86_REG_EBX])
            elif address==0x67b85:
                cpu.reg_write(UC_X86_REG_EAX,len(cstring(cpu.reg_read(UC_X86_REG_EAX))));self.return_from(cpu)
            elif address==0x679da:
                sp=cpu.reg_read(UC_X86_REG_ESP);dest,fmt,arg=struct.unpack('<3I',cpu.mem_read(sp+4,12))
                if cstring(fmt)==b'%s':cpu.mem_write(dest,cstring(arg)+b'\0')
                self.return_from(cpu)
            elif address==0x2b650:cpu.reg_write(UC_X86_REG_EAX,0);self.return_from(cpu)
            elif address in skip:self.return_from(cpu,skip[address])
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25304,argument)
        except ValueError:
            if not ending:raise
        finally:c.hook_del(token)
        if not ending:ending.append(snapshot('finished'))
        return dict(requests=requests,end=ending[0],after=base64.b64encode(self.saved()).decode())


def extract(game_dir,output_root,executable=None):
    ref=FieldReference(executable if executable is not None else unpack_game(game_dir))
    start=(game_dir/'DATA/START.DAT').read_bytes();raw=(game_dir/'DATA/AREA00.G&M').read_bytes();cases=[]
    for group,arguments in EVENTS.items():
        for argument in arguments:
            for stage in (0,2,3,65535):
                for seen in (0,1):
                    source=bytearray(start)
                    map_id=GROUP_MAP[group]
                    if group==0 and stage>=3:map_id=1
                    if group==3 and stage>=3:map_id=12
                    struct.pack_into('<I',source,96,map_id)
                    for offset,value in ((100,0),(104,0),(108,160),(112,104)):struct.pack_into('<I',source,offset,value)
                    source[116:119]=bytes((3,0,8));source[162]=17;source[325]=9;struct.pack_into('<i',source,119,123456)
                    for index,value in {0:group,1:77,6:seen,7:seen,8:stage,50:seen,99:123,299:54321}.items():struct.pack_into('<H',source,2342+index*2,value)
                    result=ref.event(raw,source,argument)
                    cases.append(dict(group=group,argument=argument,stage=stage,seen=seen,before=base64.b64encode(source).decode(),**result))
        print('원작 필드 명령·저장 기록:',group,flush=True)
    result=dict(executable_sources=EXPECTED_HASHES,source_sha256=hashlib.sha256(raw).hexdigest(),cases=cases,
        stubs='화면·음악·파일 I/O를 생략한다. 맵 번호 기록·스크롤·인물 위치·자세·음악 변수와 장소명 저장은 원작 함수로 실행한다.')
    path=output_root/'docs/restoration/field-services-native.json'
    path.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    print('필드 외부 명령:',len(cases),'조합',flush=True)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    extract(a.game_dir,a.output_root)
