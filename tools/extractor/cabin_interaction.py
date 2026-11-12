"""오두막 조사 위치·방향과 대화·벽난로 연출을 원작 함수로 추출한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from original_field import OriginalFieldMachine
from sword_story import StoryReference
from extract_buildings import front_event
from decode_opening import animation
from verify_original_map import unpack_game,read_offsets


def extract(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    offsets=read_offsets(game_dir/'MAP/AREA00.MDA');maps=(game_dir/'MAP/AREA00.MAP').read_bytes()
    front=[];ref=StoryReference(exe);start=(game_dir/'DATA/START.DAT').read_bytes();cases=[]
    for number in (1,2):
        source=maps[offsets[number]:offsets[number+1]];machine=OriginalFieldMachine(exe);machine.load_map(source)
        machine.word(0xe1e0,0)
        positions={(x,y) for y in range(0,177,8) for x in range(0,297,8)}
        positions|={(x,y) for x in (119,120,127,143,144,151,159,160,167,191,192,199,200,207,208,215,231,232,239,271,272,279) for y in (23,24,31,32,39,40,47)}
        for x,y in sorted(positions):
            for direction in (1,2,4,8):front.append([number,x,y,direction,front_event(machine,x,y,direction,True)])
        for event in sorted({r[4] for r in front if r[0]==number and r[4] is not None}):
            for state in (0,1,2):
                for stage in (0,3,4):
                    before=bytearray(start);struct.pack_into('<I',before,96,number)
                    struct.pack_into('<H',before,2342,0);struct.pack_into('<H',before,2344,77);struct.pack_into('<H',before,2354,state);struct.pack_into('<H',before,2358,stage)
                    struct.pack_into('<H',before,2540,123);ref.load_save(before);events=ref.event(game_dir,0,event)
                    if any(e['kind'] not in ('dialogue','animation') for e in events):raise ValueError(events)
                    cases.append(dict(map=number,event=event,state=state,stage=stage,before=base64.b64encode(before).decode(),after=base64.b64encode(ref.saved()).decode(),events=events))
    fire=animation((game_dir/'DATA/AUTOANI.002').read_bytes());checkpoints=OriginalFieldMachine(exe).animation_checkpoints(fire)
    report=dict(front_events=front,cases=cases,fire=fire,fire_checkpoints=checkpoints,sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('MAP/AREA00.MAP','DATA/AREA00.G&M','DATA/AUTOANI.002')})
    (output_root/'docs/cabin-interaction-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('오두막 조사 추출:',len(front),'위치·방향,',len(cases),'상태, 이벤트',sorted({r[4] for r in front if r[4] is not None}))
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
