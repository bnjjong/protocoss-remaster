"""엘가임 실내 주민 대화와 론도의 여비 지급을 원작 이벤트 함수로 확인한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX
from sword_story import StoryReference
from decode_opening import dialogue,glyph
from verify_original_title_save import objects
from verify_opening import OpeningReference
from PIL import Image
from verify_original_map import unpack_game

NPCS=[(6,9,1),(7,10,1),(7,10,2),(8,5,2),(8,5,3),(8,5,4),(10,7,1),(11,8,1)]

class IndoorReference(StoryReference):
    def event(self,game_dir,group,argument):
        services=[];self.reward_states=[]
        def hook(c,a,size,user):
            if a==0x2b3cc:
                services.append(dict(kind='hotel',id=c.reg_read(UC_X86_REG_EAX)))
                # 이번 단계는 대화까지다. 숙박 화면에서 취소한 경우처럼 돌려준다.
                self.return_from(c)
            elif a in (0x12924,0x12aa4):
                self.reward_states.append(dict(address=hex(a),money=struct.unpack('<i',c.mem_read(0x7ea34,4))[0],claimed=self.word(0xe2a0+50*2)))
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:return super().event(game_dir,group,argument)+services
        finally:self.cpu.hook_del(token)


def extract(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    ref=IndoorReference(exe);start=(game_dir/'DATA/START.DAT').read_bytes();cases=[];entries=[]
    notice=bytes(ref.cpu.mem_read(0x702b8,60)).split(b'\0')[0].decode('cp1361')
    for map_id,group,event in NPCS:
        variants={}
        for stage in (0,1,2,3,4,5,65535):
            for seen in (0,1,2,65535):
                before=bytearray(start);struct.pack_into('<I',before,96,map_id)
                struct.pack_into('<H',before,2342,group);struct.pack_into('<H',before,2344,77)
                struct.pack_into('<H',before,2356,seen);struct.pack_into('<H',before,2358,stage);struct.pack_into('<H',before,2442,seen)
                struct.pack_into('<H',before,2540,123)
                ref.load_save(before);events=ref.event(game_dir,group,event);after=ref.saved();pages=[]
                for e in events:
                    if e['kind']=='dialogue':pages+=dialogue(e['text'].encode('cp1361'))
                    elif e['kind']=='item':
                        if e!={'kind':'item','id':0,'count':50,'category':2}:raise ValueError(f'미지원 실내 보상: {e}')
                        pages.append(dict(kind='money',speaker='',portrait=-1,text=notice%50))
                    elif e['kind']!='hotel':raise ValueError(e)
                key=f'{min(stage,3)}-{int(seen!=0)}' if map_id==10 else f'{min(seen,2)}' if map_id==7 and event==1 else str(min(stage,3))
                value=dict(pages=pages,variables={str(i):struct.unpack_from('<H',after,2342+i*2)[0] for i in range(300) if before[2342+i*2:2344+i*2]!=after[2342+i*2:2344+i*2]},reward=any(e['kind']=='item' for e in events),hotel=any(e['kind']=='hotel' for e in events))
                value['variables'].pop('1',None)
                if key in variants and variants[key]!=value:raise ValueError(f'추가 조건 필요: {map_id}/{event}/{key}')
                variants[key]=value
                cases.append(dict(map=map_id,group=group,event=event,stage=stage,seen=seen,before=base64.b64encode(before).decode(),after=base64.b64encode(after).decode(),events=events,key=key,reward_states=ref.reward_states))
        entries.append(dict(map=map_id,group=group,event=event,variants=variants))
    bounds=[]
    for money in (-2147483648,-50,0,999949,999950,999999,1000000,2147483640,2147483647):
        before=bytearray(start);struct.pack_into('<i',before,119,money);struct.pack_into('<H',before,2342,7);struct.pack_into('<H',before,2358,3);struct.pack_into('<I',before,96,10)
        ref.load_save(before);events=ref.event(game_dir,7,1)
        bounds.append(dict(before=base64.b64encode(before).decode(),after=base64.b64encode(ref.saved()).decode(),events=events,reward_states=ref.reward_states))
    data=dict(entries=entries,notice=notice%50,source_sha256=hashlib.sha256((game_dir/'DATA/AREA00.G&M').read_bytes()).hexdigest())
    (_,code),(_,native_data)=objects(exe)
    font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes()
    existing=json.loads((output_root/'assets/opening/opening.json').read_text())['font']['characters']
    chars=sorted(set(''.join(p['speaker']+p['text'] for e in entries for v in e['variants'].values() for p in v['pages']))-set(existing)-{'\n'})
    native_font=OpeningReference(code,native_data,font);atlas=Image.new('RGBA',(256,max(16,((len(chars)+15)//16)*16)));widths={}
    for i,c in enumerate(chars):
        im=glyph(c,font,english,code[0x4040:0x4108]);widths[c]=im.width
        if len(c.encode('cp1361'))==2:
            bits=native_font.glyph(c.encode('cp1361'))
            if im.getchannel('A').tobytes()!=bytes(255 if bits[y*2+x//8]&(128>>(x%8)) else 0 for y in range(16) for x in range(16)):raise ValueError(c)
        atlas.paste(im,(i%16*16,i//16*16))
    font_dir=output_root/'assets/ui/indoor';font_dir.mkdir(parents=True,exist_ok=True);atlas.save(font_dir/'font.png')
    data['font']=dict(characters=''.join(chars),widths=widths)
    data['sources']={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('DATA/AREA00.G&M','HANGUL.FNT','ENGLISH.FNT')}
    folder=output_root/'assets/data/field';(folder/'indoor-dialogues.json').write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n')
    report=dict(cases=cases,bounds=bounds,sources=data['sources'],source_sha256=data['source_sha256'])
    (output_root/'docs/indoor-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('실내 대화 추출:',len(cases),'분기,',len(bounds),'금액 경계')
    return data,report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
