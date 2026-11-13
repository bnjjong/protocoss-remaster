#!/usr/bin/env python3
"""조우의 원본 연출·영상 인자/복귀·중첩 대화·저장을 실행한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import *
from verify_original_map import unpack_game,EXPECTED_HASHES
from verify_event_story import StoryServicesReference,animation_record
from verify_animation_approach import approach

def parser(exe,name,args):
 m=StoryServicesReference(exe);c=m.cpu;data=name+b'\0'+struct.pack('<3H',*args);c.mem_write(0x240000,data);m.dword(0x15a4,0x240000);captured=[]
 def hook(cpu,at,size,user):
  if at==0x67b85:
   cpu.reg_write(UC_X86_REG_EAX,len(name));m.return_from(cpu)
  elif at==0x134b8:
   p=cpu.reg_read(UC_X86_REG_EAX);raw=bytes(cpu.mem_read(p,len(name)+1)).split(b'\0')[0]
   captured.append(dict(raw=base64.b64encode(raw).decode(),args=[cpu.reg_read(r) for r in (UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]));m.return_from(cpu)
 token=c.hook_add(UC_HOOK_CODE,hook)
 try:m.call(0x261f4)
 finally:c.hook_del(token)
 return dict(input=base64.b64encode(data).decode(),**captured[0],advanced=struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000)

def verify(game_dir,output_root,executable=None):
 exe=executable if executable is not None else unpack_game(game_dir);raw=(game_dir/'DATA/AREA00.G&M').read_bytes();start=(game_dir/'DATA/START.DAT').read_bytes();cases=[]
 for y in (32,40,48):
  for state in (0,1,2,65535):
   for stage in (0,2,4):
    source=bytearray(start);struct.pack_into('<5I',source,96,3,0,0,120,y+16);source[116]=0;source[117]=0;source[118]=8
    for i,v in {0:2,1:77,2:0,4:25,6:1,7:state,8:stage,99:123,299:54321}.items():struct.pack_into('<H',source,2342+i*2,v)
    result=StoryServicesReference(exe).run(game_dir,raw,source,2,real_approach=True,keep_npcs=True)
    anim=next((r for r in result['requests'] if r['kind']=='animation'),None)
    walk=approach(exe,game_dir,3,(104,y),target=(104,48),pose=8) if anim else None
    if walk and not walk['ended']:raise ValueError('조우 접근 종료 실패')
    cases.append(dict(name=f'encounter-{len(cases)}',state=state,stage=stage,before=base64.b64encode(source).decode(),approach=walk,**result))
 opening=json.loads((output_root/'assets/opening/opening.json').read_text());opening['programs'][4]['continues_field']=True
 for command in opening['programs'][4]['commands']:
  if command['offset']==164:command['op']='C';command['value']=204
 (output_root/'assets/opening/opening.json').write_text(json.dumps(opening,ensure_ascii=False,separators=(',',':'))+'\n')
 specs=[(2,6,0,25),(3,2,0,25),(4,4,0,25),(5,5,0,24),(6,3,1,27)]
 (output_root/'assets/data/events/story.json').write_text(json.dumps(dict(animations=[animation_record(game_dir,exe,*s) for s in specs]),ensure_ascii=False,separators=(',',':'))+'\n')
 videos=dict(videos=[dict(name='#0.vis',ticks=3,music=27,restore=1,return_music=25,video='assets/encounter/encounter.pvid',audio='assets/opening/music-27/music.ogg')])
 (output_root/'assets/data/events/videos.json').write_text(json.dumps(videos,ensure_ascii=False,separators=(',',':'))+'\n')
 report=dict(executable_sources=EXPECTED_HASHES,sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('DATA/AREA00.G&M','DATA/AUTOANI.004','MAP/AREA00.MAP','GRP/#0.VIS')},cases=cases,
  parser=[parser(exe,n,a) for n,a in [(b'#0.vis',[3,27,1]),(b'OTHER.VIS',[65535,32768,65535]),(b'',[0,0,0]),('테스트.VIS'.encode('cp1361'),[32768,65535,0])]],
  stubs='실제 VM·접근·AUTOANI·중첩 이벤트·0x134B8의 분기/음악 선택·복귀·저장을 실행한다. 영상 디코딩/화면 출력·오디오 I/O·IRQ 시간 대기를 생략한다. 영상 25프레임의 디코딩·자산·표시는 별도 검사한다. NPC 시계는 전체 이벤트 비교에서 생략하고 접근 틱에서는 실행한다.')
 (output_root/'docs/restoration/encounter-event-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
 print('조우 원작:',len(cases),'상태, 연출',sum(r['approach'] is not None for r in cases),flush=True)
 return report

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
