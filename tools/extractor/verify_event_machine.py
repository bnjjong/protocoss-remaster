#!/usr/bin/env python3
"""원작의 순수 이벤트 명령·대화 대기 상태를 실행해 Kotlin 대조 기록을 만든다."""
import argparse,base64,hashlib,json,struct
from collections import Counter
from pathlib import Path
from unicorn import UC_HOOK_CODE, UcError, UC_ERR_EXCEPTION
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_ESP,UC_X86_REG_EIP
from original_field import OriginalFieldMachine
from verify_original_map import unpack_game,EXPECTED_HASHES
from event_archive import EventArchive

PURE=set(range(0xf0,0xfb))
PROFILES=[0,1,3,65535]


def initial(fill,group=0,overrides=None):
    values=[fill]*300;values[0]=group;values[1]=0x1234;values[99]=0x5aa5;values[299]=0xa55a
    for key,value in (overrides or {}).items():values[int(key)]=value
    return values


class NativeMachine:
    def __init__(self,executable):
        self.machine=OriginalFieldMachine(executable);self.texts={};self.opcodes=Counter();self.max_depth=0
        self.context=self.machine.cpu.context_save()

    def load(self,raw):
        self.archive=EventArchive(raw);count=len(self.archive.groups);m=self.machine
        m.cpu.mem_write(0x230000,raw[6:6+count*2]);m.cpu.mem_write(0x240000,self.archive.code)
        m.dword(0x15ac,0x230000);m.dword(0x15a8,0x240000);m.word(0xe50c,count)

    def trace(self,values,argument=7,budget=10000,changes=None):
        m=self.machine;c=m.cpu;dialogues=[];end=[];count=0;last=-1;opcode=-1
        c.context_restore(self.context)
        c.mem_write(0x7e2a0,struct.pack('<300H',*values));c.mem_write(0x7e4f8,bytes(20))
        m.word(0xe50c,len(self.archive.groups));m.word(0x15b0,0)
        def snapshot(kind,offset):
            depth=m.word(0x15b0)
            if depth>10:raise ValueError('원작 복귀 주소 저장소 침범')
            return dict(kind=kind,offset=offset,variables=hashlib.sha256(bytes(c.mem_read(0x7e2a0,600))).hexdigest(),
                returns=list(struct.unpack(f'<{depth}H',c.mem_read(0x7e4f8,depth*2))) if depth else [],instructions=count)
        def finish(kind,offset,**extra):
            end.append(dict(snapshot(kind,offset),**extra));c.emu_stop()
        def return_from(cpu):
            sp=cpu.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',cpu.mem_read(sp,4))[0]
            cpu.reg_write(UC_X86_REG_ESP,sp+4);cpu.reg_write(UC_X86_REG_EIP,ret)
        def hook(cpu,address,size,user):
            nonlocal count,last,opcode
            if address==0x25361:
                pc=struct.unpack('<I',cpu.mem_read(0x715a4,4))[0]-0x240000
                if not 0<=pc<len(self.archive.code):raise ValueError(f'원작 이벤트 주소 범위 오류: {pc}')
                opcode=self.archive.code[pc];last=pc
                if count>=budget:finish('budget',pc);return
                if opcode not in PURE and opcode!=3:finish('unhandled',pc,opcode=opcode);return
                depth=m.word(0x15b0);self.max_depth=max(self.max_depth,depth)
                if opcode==0xf8 and depth>=10:
                    finish('guard',pc,opcode=opcode,reason='call_storage_limit');return
                count+=1;self.opcodes[f'{opcode:02X}']+=1
            elif address==0x67b85:
                pointer=cpu.reg_read(UC_X86_REG_EAX)
                raw=bytes(cpu.mem_read(pointer,0x240000+len(self.archive.code)-pointer))
                cpu.reg_write(UC_X86_REG_EAX,raw.index(0));return_from(cpu)
            elif address==0x1f8b0:
                pointer=cpu.reg_read(UC_X86_REG_EAX)
                raw=bytes(cpu.mem_read(pointer,0x240000+len(self.archive.code)-pointer)).split(b'\0')[0]
                key=hashlib.sha256(raw).hexdigest();self.texts[key]=base64.b64encode(raw).decode()
                pc=struct.unpack('<I',cpu.mem_read(0x715a4,4))[0]-0x240000
                dialogues.append(dict(snapshot('dialogue',pc),at=last,text=key))
                for key,value in (changes or {}).get(str(len(dialogues)),{}).items():m.word(0xe2a0+int(key)*2,value)
                return_from(cpu)
            elif address==0x25707:
                pc=struct.unpack('<I',cpu.mem_read(0x715a4,4))[0]-0x240000
                finish('finished',pc,reason='CALL_LIMIT' if opcode==0xf8 else 'RETURN')
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:
            c.mem_write(0x300000,struct.pack('<I',0x3f0000));c.reg_write(UC_X86_REG_ESP,0x300000);c.reg_write(UC_X86_REG_EAX,argument)
            try:c.emu_start(0x25304,0x3f0000,count=3000000)
            except UcError as error:
                if error.errno!=UC_ERR_EXCEPTION or opcode not in (0xf3,0xf4):raise
                encoded=struct.unpack_from('<H',self.archive.code,last+5)[0]
                divisor=m.word(0xe2a0+(encoded&0x7fff)*2) if encoded&0x8000 else encoded
                if divisor!=0:raise
                # 입력값 0인 나눗셈은 원작 CPU 예외로 멈춘다.
                count-=1
                pointer=struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000
                finish('fault',last,opcode=opcode,reason='division_by_zero',native_offset=pointer)
        finally:c.hook_del(token)
        if len(end)!=1:raise ValueError('원작 이벤트 정지 지점을 확인하지 못했습니다.')
        return dict(dialogues=dialogues,end=end[0])


class Program:
    """원작 명령으로 작은 대조 프로그램을 조립한다. 실행 결과는 원작 함수가 결정한다."""
    def __init__(self,groups=8):
        self.groups=groups;self.code=bytearray(b'\xf0'+struct.pack('<3H',1,7,10)+b'\xfa\0\0')
        self.labels={'body':10};self.fixups=[(8,'end')]
    def mark(self,name):self.labels[name]=len(self.code);return self
    def emit(self,opcode,*words):self.code+=bytes([opcode])+struct.pack(f'<{len(words)}H',*words);return self
    def jump(self,opcode,label):
        self.code.append(opcode);self.fixups.append((len(self.code),label));self.code+=bytes(2);return self
    def condition(self,left,comparison,right):self.code+=b'\xf9'+struct.pack('<HBH',left,comparison,right);return self
    def dialogue(self,raw):self.code+=b'\x03'+raw+b'\0';return self
    def raw(self):
        self.mark('end').emit(0xf1)
        for offset,label in self.fixups:struct.pack_into('<H',self.code,offset,self.labels[label])
        payload=bytes(self.groups*2)+bytes(v^127 for v in self.code)
        return struct.pack('<IH',len(payload),self.groups)+payload


def synthetic_cases():
    numbers=(0,1,2,32767,32768,65535)
    for opcode in range(0xf3,0xf8):
        for left in numbers:
            for right in numbers:
                for dest in (0x8004,0x8002):
                    p=Program().emit(opcode,dest,0x8002,0x8003).emit(0xf1)
                    yield f'arithmetic-{opcode:x}-{dest:x}-{left}-{right}',p,{'2':left,'3':right},{},10000
        for value in numbers:
            for immediate in (0,1,32767):
                for a,b in ((0x8002,immediate),(immediate,0x8002)):
                    yield f'mixed-{opcode:x}-{value}-{a}-{b}',Program().emit(opcode,4,a,b).emit(0xf1),{'2':value},{},10000
    for comparison in range(6):
        for left in numbers:
            for right in numbers:
                p=Program().condition(0x8002,comparison,0x8003).jump(0xfa,'false')
                p.emit(0xf2,4,1).jump(0xfa,'end').mark('false').emit(0xf2,4,2).emit(0xf1)
                yield f'compare-{comparison}-{left}-{right}',p,{'2':left,'3':right},{},10000
    for value in numbers:
        for dest in (4,0x8004,0x812b):
            yield f'assign-{dest}-{value}',Program().emit(0xf2,dest,0x8002).emit(0xf1),{'2':value},{},10000
    p=Program().jump(0xf8,'sub').emit(0xf2,4,12).emit(0xf1).mark('sub')
    p.emit(0xf2,4,10).jump(0xf8,'inner').emit(0xf7,4,0x8004,1).emit(0xf1)
    p.mark('inner').dialogue('카만/E'.encode('cp1361')).emit(0xf7,4,0x8004,1).emit(0xf1)
    yield 'nested-call-dialogue',p,{}, {},10000
    for groups in (1,3,10,12):yield f'call-limit-{groups}',Program(groups).jump(0xf8,'body'),{}, {},10000
    yield 'call-limit-before-reading-operand',Program(1).emit(0xf8),{}, {},10000
    yield 'jump-budget',Program().jump(0xfa,'body'),{}, {},137
    p=Program().dialogue(b'first/E').emit(0xf2,4,0x8063).dialogue(b'second/E').emit(0xf1)
    yield 'dialogue-host-variable',p,{}, {'1':{'99':54321}},10000
    yield 'unhandled-does-not-consume',Program().emit(2).emit(0xf2,4,22).emit(0xf1),{}, {},10000


def verify(game_dir,output_root,executable=None):
    native=NativeMachine(executable if executable is not None else unpack_game(game_dir));cases=[];sources={};synthetics=[]
    for area in range(6):
        name=f'DATA/AREA{area:02}.G&M';raw=(game_dir/name).read_bytes();sources[name]=hashlib.sha256(raw).hexdigest();native.load(raw)
        entries=[]
        for group,g in enumerate(native.archive.groups):
            arguments={a for a,_ in g.routes};missing=next(a for a in range(65536) if a not in arguments)
            entries.extend((group,a) for a in sorted(arguments|{missing,65535}))
        for fill in PROFILES:
            for group,argument in entries:
                cases.append(dict(area=area,group=group,argument=argument,fill=fill,**native.trace(initial(fill,group),argument)))
        print('원본 이벤트 진입·초기값 조합:',area,len(entries)*len(PROFILES),'개',flush=True)
    for name,program,overrides,changes,budget in synthetic_cases():
        raw=program.raw();native.load(raw)
        synthetics.append(dict(name=name,raw=base64.b64encode(raw).decode(),overrides=overrides,changes=changes,budget=budget,
            **native.trace(initial(0,overrides=overrides),budget=budget,changes=changes)))
    result=dict(scope='순수 명령과 대화 대기를 대조하며 미지원 외부 명령 직전에 멈춘다. 전체 퀘스트 실행 검사가 아니다.',
        executable_sources=EXPECTED_HASHES,sources=sources,profiles=PROFILES,
        functions=dict(loop='0x25304',arithmetic='0x25150',assign='0x25844',condition='0x258F0',dialogue='0x25DD4'),
        opcodes=dict(sorted(native.opcodes.items())),max_depth=native.max_depth,texts=native.texts,cases=cases,synthetic=synthetics)
    path=output_root/'docs/restoration/event-machine-native.json'
    path.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    print('원작 실행 기록:',len(cases),'원본 조합,',len(synthetics),'경계·대기 표본,',len(native.texts),'문자열',flush=True)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    verify(a.game_dir,a.output_root)
