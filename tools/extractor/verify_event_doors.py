#!/usr/bin/env python3
"""문 이벤트의 원본 명령·타일 변경·저장·정면 이벤트 해제를 실행해 기록한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX
from sword_story import StoryReference
from event_archive import EventArchive
from extract_buildings import front_event
from verify_original_map import unpack_game,read_offsets,EXPECTED_HASHES

class DoorReference(StoryReference):
    def parser(self,value):
        c=self.cpu;c.mem_write(0x240000,struct.pack('<H',value));self.dword(0x15a4,0x240000)
        captured=[]
        def hook(cpu,address,size,user):
            if address==0x11cdc:
                captured.append(cpu.reg_read(UC_X86_REG_EAX));self.return_from(cpu)
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25e58)
        finally:c.hook_del(token)
        return dict(input=value,id=captured[0],advanced=struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000)

    def event_door(self,game_dir,source,map_source,soc,argument):
        self.load_save(source);self.load_map(map_source);self.word(0xe1e0,0)
        c=self.cpu;raw=(game_dir/'DATA/AREA00.G&M').read_bytes();archive=EventArchive(raw)
        c.mem_write(0x230000,raw[6:6+len(archive.groups)*2]);c.mem_write(0x240000,archive.code)
        self.dword(0x15ac,0x230000);self.dword(0x15a8,0x240000);self.word(0xe50c,len(archive.groups));self.word(0x15b0,0)
        c.mem_write(0x7b880,soc[66770:68770]);c.mem_write(0x3e0000,b'\xc3');self.dword(0xfd40,0x3e0000)
        sx,sy,x,y=struct.unpack_from('<4I',source,100)
        self.dword(0xe1e4,x);self.dword(0xe1e8,y);self.byte(0xe1fc,source[118]);self.byte(0xe1fb,source[117]);self.byte(0xe1f8,1)
        requests=[];steps=0;last=0
        def pc():return struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000
        def snapshot(kind,**extra):return dict(kind=kind,offset=pc(),at=last,instructions=steps,variables=hashlib.sha256(bytes(c.mem_read(0x7e2a0,600))).hexdigest(),**extra)
        skip={0x2d1e6:0,0x2cb5f:8,0x243f0:0,0x65760:0,0x22148:0,0x2ca5e:8,0x67b85:0}
        def hook(cpu,address,size,user):
            nonlocal steps,last
            if address==0x25361:
                last=pc();op=archive.code[last]
                if op not in set(range(0xf0,0xfb))|{3,0xa}:raise ValueError(f'미지원 문 이벤트 {op:#x}')
                steps+=1
            elif address==0x11cdc:requests.append(snapshot('door',id=cpu.reg_read(UC_X86_REG_EAX)))
            elif address==0x1f8b0:
                raw=bytes(c.mem_read(cpu.reg_read(UC_X86_REG_EAX),4096)).split(b'\0')[0]
                requests.append(snapshot('dialogue',raw=base64.b64encode(raw).decode()));self.return_from(cpu)
            elif address in skip:
                if address==0x67b85:cpu.reg_write(UC_X86_REG_EAX,bytes(c.mem_read(cpu.reg_read(UC_X86_REG_EAX),4096)).index(0))
                self.return_from(cpu,skip[address])
        token=c.hook_add(UC_HOOK_CODE,hook)
        try:self.call(0x25304,argument)
        finally:c.hook_del(token)
        ending=snapshot('finished');after=self.saved()
        changed=bytes(c.mem_read(0x200000,self.width*self.height*4));original=map_source[48:48+self.width*self.height*4]
        changes=[dict(index=i,tid=t,attr=a) for i,((t,a),before) in enumerate(zip(struct.iter_unpack('<HH',changed),struct.iter_unpack('<HH',original))) if (t,a)!=before]
        pose=self.byte(0xe1fc);direction=self.byte(0xe1f8)
        event=front_event(self,x+sx*8-16,y+sy*8-16,1,False)
        return dict(requests=requests,end=ending,after=base64.b64encode(after).decode(),changes=changes,pose=pose,direction=direction,event_after=event)

def extract(game_dir,output_root,executable=None):
    ref=DoorReference(executable if executable is not None else unpack_game(game_dir))
    metadata=json.loads((output_root/'assets/data/field/field.json').read_text());start=(game_dir/'DATA/START.DAT').read_bytes()
    maps=(game_dir/'MAP/AREA00.MAP').read_bytes();offs=read_offsets(game_dir/'MAP/AREA00.MDA')
    soc=(game_dir/'MAP/AREA00.SOC').read_bytes();so=read_offsets(game_dir/'MAP/AREA00.SDA');cases=[]
    for number in (0,4,12):
        source=maps[offs[number]:offs[number+1]];m=metadata['maps'][str(number)];block=soc[so[source[32]]:so[source[32]]+70818]
        for door in m['doors']:
            x,y=door['x'],door['y'];event=0
            if number!=0:
                event=next(r[4] for r in m['rectangles'] if x//8+1 in range(r[0],r[2]+1) and y//8+1 in range(r[1],r[3]+1))
            for stage in (0,2,3,65535):
                for scroll in (0,1):
                    before=bytearray(start);sx=max(0,x//8-20) if scroll else 0;sy=max(0,y//8-12) if scroll else 0
                    struct.pack_into('<5I',before,96,number,sx,sy,x+16-sx*8,y+16-sy*8);before[116:119]=bytes((1,door.get('layer',0),2))
                    for i,v in {0:1 if number==0 else 3,1:77,8:stage,99:123,299:54321}.items():struct.pack_into('<H',before,2342+i*2,v)
                    value=ref.event_door(game_dir,before,source,block,event)
                    cases.append(dict(map=number,x=x,y=y,layer=door.get('layer',0),argument=event,stage=stage,scroll=scroll,before=base64.b64encode(before).decode(),**value))
        print('원작 문 명령:',number,flush=True)
    report=dict(executable_sources=EXPECTED_HASHES,cases=cases,parser=[ref.parser(v) for v in (0,1,255,32768,65535)],sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('DATA/AREA00.G&M','MAP/AREA00.MAP','MAP/AREA00.SOC')},stubs='문 타일·속성·자세·방향 변경은 원작 함수를 실행한다. 화면 그리기·버퍼 복사·키/음악 대기는 생략한다. 원작 저장 함수가 아닌 기존 로더의 저장 메모리 구간을 비교한다.')
    (output_root/'docs/restoration/event-doors-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('문 명령 기록:',len(cases),'조합',flush=True)
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
