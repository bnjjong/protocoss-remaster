#!/usr/bin/env python3
"""원작 0x11B20의 접근 보행·스크롤·주민을 틱마다 실행한다. EXE와 메모리는 저장하지 않는다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from sword_story import StoryReference
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import *
from verify_original_map import unpack_game
from verify_original_map import read_offsets
from verify_npcs import snapshot

def npc_visible(exe,x,y):
 m=StoryReference(exe);c=m.cpu;m.word(0xe1e0,1);m.dword(0x157c,0x220000);m.dword(0x1578,0x230000)
 m.dword(0xe1e4,160);m.dword(0xe1e8,104);m.dword(0xd80c,0);m.dword(0xd810,0);m.dword(0xe530,0);m.dword(0xe52c,0);m.dword(0xe548,0)
 row=bytearray(54);struct.pack_into('<ii',row,4,x,y);c.mem_write(0x220000,bytes(row));drawn=[]
 def hook(cpu,at,size,user):
  if at==0x246bf:drawn.append(True)
  if at in (0x2d11a,0x20ce8):m.return_from(cpu)
 token=c.hook_add(UC_HOOK_CODE,hook)
 try:m.call(0x243f0)
 finally:c.hook_del(token)
 return bool(drawn)

def approach(exe,game_dir,mapnum,world,target=(256,144),step=1,pose=8,layer=0,scroll=None,npcs=True,max_ticks=1600):
 m=StoryReference(exe);s=bytearray((game_dir/'DATA/START.DAT').read_bytes());off=read_offsets(game_dir/'MAP/AREA00.MDA');raw=(game_dir/'MAP/AREA00.MAP').read_bytes();source=raw[off[mapnum]:off[mapnum+1]]
 w,h=source[2]*40,source[3]*25
 sx,sy=scroll or (max(0,min(w-40,(world[0]-144)//8)),max(0,min(h-25,(world[1]-88)//8)))
 tsx,tsy=max(0,min(w-40,(target[0]-144)//8)),max(0,min(h-25,(target[1]-88)//8))
 struct.pack_into('<5I',s,96,mapnum,sx,sy,world[0]-sx*8+16,world[1]-sy*8+16);s[117]=layer;s[118]=pose
 m.load_save(s);m.load_map(source);m.dword(0xd804,w-40);m.dword(0xd808,h-25)
 if not npcs:m.word(0xe1e0,0)
 for p,v in [(0xd80c,sx),(0xd810,sy),(0xe52c,0),(0xe530,0),(0xe548,0),(0x7264,1)]:m.dword(p,v)
 m.word(0xe510,step);m.word(0xe512,0);m.byte(0xe1fa,0)
 c=m.cpu;c.mem_write(0x3e0000,b'\xc3');m.dword(0xfd44,0x3e0000)
 frames=[];bios=0;phase=0.;ended=True
 def val(p):return struct.unpack('<i',c.mem_read(p,4))[0]
 def hook(cpu,at,size,user):
  nonlocal bios,phase,ended
  if at==0x23da4:
   phase+=1193182/(65536*70)
   while phase>=1:
    phase-=1;bios+=1
    if bios%4==0:m.word(0xe1de,1)
    if bios%60==0:m.word(0xe1e2,1)
  elif at==0x243f0:
   vx=val(0x7d80c)*8+val(0x7e52c);vy=val(0x7d810)*8+val(0x7e530)
   frames.append(dict(hero=[vx+val(0x7e1e4)-16,vy+val(0x7e1e8)-16,m.byte(0xe1fc),m.byte(0xe1fb),m.byte(0xe1f8)],scroll=[vx,vy],npcs=snapshot(m,source[34]) if npcs else []))
   if len(frames)>=max_ticks:ended=False;cpu.emu_stop();return
   m.return_from(cpu)
  elif at in skips:m.return_from(cpu,skips[at])
 skips={0x1dd64:0,0x22148:0,0x1f108:4,0x2ca5e:8,0x2cb5f:8,0x2b6f0:0}
 token=c.hook_add(UC_HOOK_CODE,hook)
 try:
  c.mem_write(0x300000,struct.pack('<I',0x3f0000));c.reg_write(UC_X86_REG_ESP,0x300000)
  for r,v in zip((UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX),(tsx,tsy,target[0]-tsx*8+16,target[1]-tsy*8+16)):c.reg_write(r,v)
  c.emu_start(0x11b20,0x3f0000,count=5000000)
  if ended and c.reg_read(UC_X86_REG_EIP)!=0x3f0000:raise ValueError('실행 한도')
 finally:c.hook_del(token)
 return dict(map=mapnum,start=[*world,pose,layer],scroll=[sx*8,sy*8],target=[*target,tsx*8,tsy*8],step=step,npcs=npcs,ended=ended,frames=frames)


def verify(game_dir,output_root,executable=None):
 exe=executable if executable is not None else unpack_game(game_dir)
 rows=[]
 for step in (1,2,4,8):
  for world in ((224,144),(248,144),(264,144),(288,144),(256,136),(256,152),(248,136),(264,152)):
   rows.append(approach(exe,game_dir,4,world,step=step))
 for pose in range(16):rows.append(approach(exe,game_dir,4,(256,144),pose=pose))
 for world in ((248,160),(256,176)):
  rows.append(approach(exe,game_dir,4,world,max_ticks=40))
 # 화면 안 보행과 지도 끝의 카메라 고정을 별도로 확인한다.
 for world,target in (((24,32),(40,32)),((40,32),(24,32)),((136,32),(160,32)),((160,32),(136,32)),((256,144),(280,144))):
  rows.append(approach(exe,game_dir,4,world,target=target,max_ticks=80))
 from verify_event_story import StoryServicesReference
 previous=json.loads((output_root/'docs/restoration/event-story-native.json').read_text())
 base=next(r for r in previous['cases'] if any(c['kind']=='animation' and c['id']==5 for c in r['requests']))
 stories=[];raw=(game_dir/'DATA/AREA00.G&M').read_bytes()
 for index,row in enumerate(rows):
  if not row['ended'] or row['step']!=1 or row['target'][:2]!=[256,144]:continue
  source=bytearray(base64.b64decode(base['before']));x,y,pose,layer=row['start'];sx,sy=row['scroll']
  struct.pack_into('<5I',source,96,4,sx//8,sy//8,x-sx+16,y-sy+16);source[117]=layer;source[118]=pose
  result=StoryServicesReference(exe).run(game_dir,raw,source,15,real_approach=True)
  stories.append(dict(case=index,before=base64.b64encode(source).decode(),after=result['after']))
 visibility=[[x,y,npc_visible(exe,x,y)] for x in (-24,-17,-16,-15,0,311,312,319,320) for y in (-24,-17,-16,-15,0,191,192,199,200)]
 report=dict(sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('AMOS.EXE','PROTOCOS.FNT','MAP/AREA00.MAP','DATA/AREA00.G&M')},cases=rows,stories=stories,visibility=visibility)
 path=output_root/'docs/restoration/animation-approach-native.json';path.write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
 print('원작 접근:',len(rows),'표본,',sum(len(r['frames']) for r in rows),'틱, 도착',sum(r['ended'] for r in rows),flush=True)
 return report

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
