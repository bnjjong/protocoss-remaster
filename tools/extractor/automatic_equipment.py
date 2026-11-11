"""원작 자동 장비·해제의 전체 저장 상태, 이동 속도, 탭 입력 결과를 기록한다."""
import argparse,base64,hashlib,json,random,struct
from pathlib import Path
from equipment_reference import EquipmentReference
from party_equipment import changes
from verify_original_map import unpack_game


def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    start=(game_dir/'DATA/START.DAT').read_bytes();ref=EquipmentReference(exe)
    rng=random.Random(250166);cases=[];controllers=[]
    def fixture(character,variant):
        raw=bytearray(start)
        for c in range(11):raw[347+c*153]=int(c in (0,character,(character+1)%11))
        raw[162:337]=bytes([2])*175
        offset=473+153*character;raw[offset:offset+6]=bytes([125,107,0,90,157,174])
        raw[387+153*character]=12;struct.pack_into('<H',raw,2936,2)
        struct.pack_into('<H',raw,2540,4321)
        if variant=='empty':raw[162:337]=bytes(175);raw[offset:offset+4]=bytes([255])*4
        elif variant=='overflow':raw[162:337]=bytes([255])*175
        elif variant=='twohand':
            raw[162:337]=bytes(175)
            for i in (0,3,90,125,107):raw[162+i]=2
        elif variant=='shield-only':raw[162:337]=bytes(175);raw[offset:offset+4]=bytes([255])*4;raw[252]=2
        elif variant=='no-accessory':raw[offset+4:offset+6]=bytes([255])*2;raw[387+153*character]=0
        elif variant=='random':
            raw[162:337]=bytes(rng.choice((0,0,0,1,2,99,255)) for _ in range(175))
            raw[offset:offset+4]=bytes(rng.choice((255,*range(141))) for _ in range(4))
            for j in range(6):struct.pack_into('<H',raw,360+153*character+j*4,rng.choice((0,1,999,60000,65535)))
        return raw
    def record(name,character,raw,overrides=None):
        ref.prepare(game_dir,raw)
        assert ref.saved()==raw, "원작 준비 단계가 입력 저장을 바꿨습니다."
        for item,stats in (overrides or []):ref.cpu.mem_write(0x180000+84*item+35,struct.pack('<6h',*stats))
        steps=[];before=bytes(raw)
        for action in ('unequip','unequip','equip','equip','unequip','equip'):
            events=ref.invoke(0x166d0 if action=='equip' else 0x16658,character)
            after=ref.saved();steps.append(dict(action=action,after=changes(before,after),step=ref.word(0xe510)))
            before=after
        cases.append(dict(name=name,character=character,before=base64.b64encode(raw).decode(),overrides=overrides or [],steps=steps))
    for c in range(11):
        for variant in ('full','empty','overflow','twohand','shield-only','no-accessory'):
            record(f'{c}-{variant}',c,fixture(c,variant))
        for i in range(8):record(f'{c}-random-{i}',c,fixture(c,'random'))
    # 원본 데이터에서 드문 점수 경계도 원작 기계어로 실행한다. 디스크 DB는 바꾸지 않는다.
    for name,overrides in [
        ('tie',[(0,[8,0,0,0,0,0]),(1,[8,0,0,0,0,0])]),
        ('nonpositive',[(0,[-1,0,0,0,0,0]),(1,[0,0,0,0,0,0])]),
        ('signed-sum',[(0,[100,-99,0,0,0,0]),(1,[2,0,0,0,0,0])])]:
        raw=fixture(0,'empty');raw[162]=raw[163]=2
        record(name,0,raw,overrides)
    for name,keys in [
        ('tab-bounds',[75,75,77,77,77,77,75,75,75,1]),
        ('equip-stay',[77,28,28,1]),('unequip-stay',[77,77,57,57,1]),
        ('party-wrap',[72,77,28,80,28,80,77,28,1]),
        ('sparse-party',[80,77,28,80,77,57,72,75,28,1])]:
        raw=fixture(0,'full')
        for c in range(11):raw[347+c*153]=int(c<5)
        if name=='sparse-party':
            for c in range(11):raw[347+c*153]=int(c in (0,3,10))
        ref.prepare(game_dir,raw)
        assert ref.saved()==raw, "원작 준비 단계가 입력 저장을 바꿨습니다."
        events=ref.invoke(0x16890,0,keys=keys)
        controllers.append(dict(name=name,keys=keys,before=base64.b64encode(raw).decode(),after=changes(raw,ref.saved()),step=ref.word(0xe510),
            actions=[e for e in events if e['kind']=='automatic'],tabs=[int(e['image'].split('-')[1])-233 for e in events if e['kind']=='image' and e['image'] in ('imenu-233','imenu-234','imenu-235')]))
    report=dict(sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('AMOS.EXE','PROTOCOS.FNT','DATA/WEAPON.LST','DATA/START.DAT')},cases=cases,controllers=controllers)
    (output_root/'docs/automatic-equipment-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('자동 장비 원작 검사:',len(cases),'표본,',sum(len(c['steps']) for c in cases),'상태 변경,',len(controllers),'입력 순서')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
