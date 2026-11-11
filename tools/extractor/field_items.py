"""원작의 파티원 대상 아이템 효과·수량·대상 선택을 실행하고 저장 전체를 기록한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from equipment_reference import EquipmentReference
from party_equipment import changes
from verify_original_map import unpack_game

class ItemReference(EquipmentReference):
    def target(self,item,keys=(28,1)):
        self.invoke(0x28408,0)
        self.cpu.mem_write(0x7e610,struct.pack('<HHH',item,0,0))
        return self.invoke(0x1b844,0,keys=keys)


def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    start=(game_dir/'DATA/START.DAT').read_bytes();ref=ItemReference(exe)
    fixtures=[];cases=[];navigation=[]
    ids=[*range(17),32,33]
    variants=[(1,1,2),(1,1,1),(1,0,1),(2,1,2),(3,1,2),(4,1,2),(5,1,2),(5,3,2),(1,2,255),(3,3,2)]
    for c in range(11):
        for status,health,count in variants:
            raw=bytearray(start)
            for ch in range(11):raw[347+153*ch]=int(ch in (0,c,(c+1)%11))
            off=153*c;raw[472+off]=status;raw[477+off]=157;raw[478+off]=174;raw[387+off]=12
            struct.pack_into('<H',raw,2936,2);struct.pack_into('<H',raw,2540,4321)
            hp,hm,mp,mm=[(70,70,80,80),(13,70,7,80),(65530,65535,65530,65535),(0,70,0,80)][health]
            struct.pack_into('<4H',raw,352+off,hp,hm,mp,mm)
            struct.pack_into('<i',raw,348+off,1 if count==1 else 123456)
            struct.pack_into('<H',raw,384+off,65530 if health==2 else 300)
            raw[123:162]=bytes([count])*39
            fixture=len(fixtures);fixtures.append(base64.b64encode(raw).decode())
            party=[ch for ch in range(11) if raw[347+153*ch]];index=party.index(c)
            prefix=[77]*index
            for item in ids:
                ref.prepare(game_dir,raw);assert ref.saved()==raw
                events=ref.target(item,keys=(*prefix,28,28,1))
                cases.append(dict(fixture=fixture,character=c,item=item,after=changes(raw,ref.saved()),step=ref.word(0xe510),
                    effects=[e['id'] for e in events if e['kind']=='effect']))
    # 클릭 좌표를 추정하지 않고 원작 대상 선택의 모든 방향 전환을 수집한다.
    from unicorn import UC_HOOK_CODE
    from unicorn.x86_const import UC_X86_REG_EAX
    for count in range(1,6):
        raw=bytearray(start);raw[123]=2
        for c in range(11):raw[347+153*c]=int(c<count)
        for index in range(count):
            prefix=[80]*(index//3)+[77]*(index%3)
            for direction,key in [('up',72),('right',77),('down',80),('left',75)]:
                ref.prepare(game_dir,raw);seen=[]
                token=ref.cpu.hook_add(UC_HOOK_CODE,lambda cpu,a,s,u:seen.append(cpu.reg_read(UC_X86_REG_EAX)) if a==0x1b218 else None)
                try:ref.target(0,keys=(*prefix,key,1))
                finally:ref.cpu.hook_del(token)
                navigation.append(dict(count=count,index=index,direction=direction,next=seen[-1]))
    report=dict(sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('AMOS.EXE','PROTOCOS.FNT','DATA/ITEM.LST','DATA/START.DAT')},ids=ids,fixtures=fixtures,cases=cases,navigation=navigation)
    (output_root/'docs/field-items-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('대상 아이템 원작 검사:',len(cases),'표본,',len(navigation),'방향 전환',flush=True)
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
