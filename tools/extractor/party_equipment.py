"""모든 파티원·슬롯의 목록·장착·해제·특수 상태를 원작 함수에 대조한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from equipment_reference import EquipmentReference
from verify_original_map import unpack_game


def changes(before,after):return [[i,b] for i,(a,b) in enumerate(zip(before,after)) if a!=b]


def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    start=(game_dir/'DATA/START.DAT').read_bytes();ref=EquipmentReference(exe)
    fixtures=[];cases=[];matrices=[];rejections=[]
    for character in range(11):
        for slot in range(6):
            raw=bytearray(start);raw[162:337]=bytes([2])*175
            raw[347+153*character]=1;raw[347+153*((character+1)%11)]=1
            raw[473+153*character:479+153*character]=bytes([255])*6
            raw[477+153*character]=142 if slot!=4 else 255
            struct.pack_into('<H',raw,2342+99*2,4321)
            ref.prepare(game_dir,raw);choices=ref.candidates(character,slot)
            matrices.append(dict(character=character,slot=slot,ids=choices))
            fixture=len(fixtures);fixtures.append(base64.b64encode(raw).decode())
            for item in choices:
                ref.prepare(game_dir,raw);ref.begin_slot(character,slot);detached=ref.saved();step_before=ref.word(0xe510)
                preview=ref.invoke(0x15808,character,slot,item)
                events=ref.equip(item,character,slot);after=ref.saved();step=ref.word(0xe510)
                cases.append(dict(fixture=fixture,character=character,slot=slot,item=item,
                    detached=changes(raw,detached),after=changes(detached,after),step_before=step_before,step=step,
                    preview=[int(e['text']) for e in preview if e['kind']=='number'],effects=[e['id'] for e in events if e['kind']=='effect']))
    # 교체·수량 넘침·구슬 해제·양손/방패와 양쪽 구슬 분기를 별도 실행한다.
    transitions=[]
    for character in (0,1,5,10):
        for slot,old,count in [(0,125,99),(2,0,255),(4,163,2),(5,174,2),(4,157,2)]:
            raw=bytearray(start);raw[347+153*character]=1
            raw[473+153*character+slot]=old;raw[162+old]=count;raw[387+153*character]=old-162 if old>=163 else 0
            struct.pack_into('<H',raw,2936,2 if old==157 else 1)
            ref.prepare(game_dir,raw);ref.begin_slot(character,slot)
            transitions.append(dict(character=character,slot=slot,before=base64.b64encode(raw).decode(),after=changes(raw,ref.saved()),step=ref.word(0xe510)))
        for slot,item,other,other_item in [(2,3,3,90),(3,90,2,3),(4,163,5,174),(5,174,4,163)]:
            raw=bytearray(start);raw[347+153*character]=1;raw[162+item]=1
            raw[473+153*character+other]=other_item
            ref.prepare(game_dir,raw)
            if item not in ref.candidates(character,slot):continue
            ref.begin_slot(character,slot);before=ref.saved();events=ref.equip(item,character,slot)
            rejections.append(dict(character=character,slot=slot,item=item,before=base64.b64encode(before).decode(),after=changes(before,ref.saved()),
                rejected=any(e['kind']=='effect' and e['id']==13 for e in events)))
    sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('AMOS.EXE','PROTOCOS.FNT','DATA/WEAPON.LST','DATA/START.DAT')}
    report=dict(sources=sources,fixtures=fixtures,matrices=matrices,cases=cases,transitions=transitions,restrictions=rejections)
    (output_root/'docs/party-equipment-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('파티 장비 원작 검사:',len(cases),'장착,',len(transitions),'해제,',len(rejections),'제한 분기')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
