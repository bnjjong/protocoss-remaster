#!/usr/bin/env python3
"""엘가임 건물의 출입 분기와 문 타일·통과 판정을 원작 함수로 추출한다."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from original_field import OriginalFieldMachine
from verify_original_map import read_offsets,unpack_game
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_ESP,UC_X86_REG_EIP

GROUP_MAPS={3:[4,12],4:[5],5:[8],6:[9],7:[10],8:[11],9:[6],10:[7]}
ROUTES={3:range(2,9),4:[0,1],5:[0,1],6:[0],7:[0],8:[0],9:[0],10:[4]}

def front_event(machine,x,y,direction,action):
    """이동·조작 키의 실제 이벤트 호출을 기록한다. 이벤트 본문만 별도 검사로 넘긴다."""
    machine.dword(0xd7fc,0);machine.dword(0xd800,0)
    machine.dword(0xe1e4,x+16);machine.dword(0xe1e8,y+16)
    machine.byte(0xe1f8,direction);machine.byte(0xe1fc,{1:0,2:4,4:8,8:12}[direction]);seen=[]
    def hook(c,address,size,user):
        if address!=0x25304:return
        seen.append(c.reg_read(UC_X86_REG_EAX))
        sp=c.reg_read(UC_X86_REG_ESP);ret=struct.unpack('<I',c.mem_read(sp,4))[0]
        c.reg_write(UC_X86_REG_ESP,sp+4);c.reg_write(UC_X86_REG_EIP,ret)
    token=machine.cpu.hook_add(UC_HOOK_CODE,hook)
    try:machine.call(0x1204c if action else 0x20c10)
    finally:machine.cpu.hook_del(token)
    if len(seen)>1:raise ValueError('정면 이벤트가 여러 번 실행되었습니다.')
    return seen[0] if seen else None


def table(machine):
    data=bytearray()
    for layer in range(3):
        for y in range(machine.height):
            for x in range(machine.width):
                for direction in (1,2,4,8):
                    allowed,height=machine.query(x*8,y*8,direction,layer)
                    data.append(allowed | height<<4)
    return data


def extract(game_dir, output_root, executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    raw=(game_dir/'DATA/AREA00.G&M').read_bytes();cases=[];routes=[]
    for group,arguments in ROUTES.items():
        for argument in arguments:
            variants={}
            for stage in range(6):
                result=OriginalFieldMachine(exe).trace_event(raw,group,argument,{8:stage,99:123})
                if any(e['kind'] not in ('effect','warp') for e in result['events']):raise ValueError('건물 출입에 미지원 명령이 있습니다.')
                warp=[e['args'] for e in result['events'] if e['kind']=='warp']
                if len(warp)!=1:raise ValueError('건물 출입 목적지가 하나가 아닙니다.')
                cases.append(dict(group=group,argument=argument,stage=stage,result=result))
                value=dict(warp=warp[0],group=result['variables']['0'],effects=[e['id'] for e in result['events'] if e['kind']=='effect'])
                canonical='0' if stage<3 else '3'
                if canonical in variants and variants[canonical]!=value:raise ValueError('추가 진행 분기가 필요합니다.')
                variants[canonical]=value
            routes.append(dict(group=group,argument=argument,variants=variants))
    folder=output_root/'assets/data/field';metadata=json.loads((folder/'field.json').read_text())
    map_raw=(game_dir/'MAP/AREA00.MAP').read_bytes();offsets=read_offsets(game_dir/'MAP/AREA00.MDA')
    soc=(game_dir/'MAP/AREA00.SOC').read_bytes();so=read_offsets(game_dir/'MAP/AREA00.SDA');door_report=[];combined=[]
    for number in (4,12):
        source=map_raw[offsets[number]:offsets[number+1]];m=metadata['maps'][str(number)];m['doors']=[]
        original=list(struct.iter_unpack('<HH',source[48:48+120*75*4]))
        base=(folder/m['file']).read_bytes()
        for rectangle in m['rectangles']:
            if rectangle[4] not in (0,9):continue
            x1,y1,x2,y2,_=rectangle
            cells=[(x,y) for y in range(y1,y2+1) for x in range(x1,x2+1) if original[y*120+x][1]&8]
            if len(cells)!=1:raise ValueError('문 앞 이벤트 타일이 하나가 아닙니다.')
            tx,ty=cells[0];x=(tx-1)*8;y=(ty-1)*8
            machine=OriginalFieldMachine(exe);machine.load_map(source);machine.word(0xe1e0,0)
            start=so[source[32]];changed=machine.open_door(soc[start:start+70818],x,y)
            changes=[dict(index=i,tid=t,attr=a) for i,((t,a),before) in enumerate(zip(struct.iter_unpack('<HH',changed),original)) if (t,a)!=before]
            opened=table(machine)
            patches=[[i,value] for i,value in enumerate(opened) if base[i]!=value]
            door=dict(x=x,y=y,direction=1,changes=changes,movement=patches)
            m['doors'].append(door)
            door_report.append(dict(map=number,x=x,y=y,changes=changes,movement_changes=len(patches),sha256=hashlib.sha256(opened).hexdigest()))
            print('원작 문 추출:',number,x,y,len(changes),'셀,',len(patches),'이동 판정',flush=True)
        machine=OriginalFieldMachine(exe);machine.load_map(source);machine.word(0xe1e0,0)
        for door in m['doors']:machine.open_door(soc[start:start+70818],door['x'],door['y'])
        combined.append(dict(map=number,sha256=hashlib.sha256(table(machine)).hexdigest()))
    # 실내의 실제 주민 이동도 원작에서 실행해, 새로 포함한 레코드를 대조한다.
    motions=[]
    for number in range(5,12):
        source=map_raw[offsets[number]:offsets[number+1]]
        machine=OriginalFieldMachine(exe);machine.load_map(source);machine.dword(0x7264,1)
        machine.dword(0xd7fc,0);machine.dword(0xd800,0);machine.dword(0xe1e4,160);machine.dword(0xe1e8,184)
        phase=0.0;bios=0;digest=hashlib.sha256()
        for tick in range(700):
            phase+=1193182/(65536*70)
            while phase>=1:
                phase-=1;bios+=1
                if bios%4==0:machine.word(0xe1de,1)
                if bios%60==0:machine.word(0xe1e2,1)
            machine.call(0x23da4)
            for i in range(source[34]):
                v=struct.unpack('<12I6B',machine.cpu.mem_read(0x220000+i*54,54))
                digest.update(struct.pack('<7i',v[1],v[2],v[5],v[6],v[12],v[13],v[16]))
        motions.append(dict(map=number,ticks=700,hero=[144,168],sha256=digest.hexdigest()))
    (folder/'field.json').write_text(json.dumps(metadata,ensure_ascii=False,separators=(',',':'))+'\n')
    result=dict(groups={str(g):maps for g,maps in GROUP_MAPS.items()},routes=routes)
    (folder/'buildings.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    front=[]
    for number in range(4,13):
        source=map_raw[offsets[number]:offsets[number+1]];machine=OriginalFieldMachine(exe);machine.load_map(source)
        positions=set()
        for x1,y1,x2,y2,event in metadata['maps'][str(number)]['rectangles']:
            for x in range(max(0,x1-4),min(machine.width-3,x2+4)+1):
                for y in range(max(0,y1-4),min(machine.height-3,y2+2)+1):positions.add((x*8,y*8))
        for x,y in sorted(positions):
            for direction in (1,2,4,8):front.append([number,x,y,direction,front_event(machine,x,y,direction,False),front_event(machine,x,y,direction,True)])
    gates=[dict(stage=stage,result=OriginalFieldMachine(exe).trace_event(raw,3,9,{8:stage},capture_dialogue=True)) for stage in range(6)]
    report=dict(source_sha256=hashlib.sha256(raw).hexdigest(),cases=cases,doors=door_report,combined=combined,motions=motions,gates=gates,front_events=front)
    (output_root/'docs/buildings-native-verification.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('원작 건물 출입 추출:',len(cases),'분기,',len(door_report),'문',flush=True)
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args()
    extract(a.game_dir,a.output_root)
