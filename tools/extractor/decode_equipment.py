"""소지품·카만 무기 메뉴의 원작 자료와 상태 비교 표본을 만든다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from PIL import Image
from decode_title import decode_img_frame
from decode_opening import glyph
from equipment_reference import EquipmentReference
from verify_original_map import unpack_game
from verify_original_title_save import objects
from verify_original_user_sprite import OriginalSpriteRenderer
from verify_opening import OpeningReference


def records(raw,size):
    result=[]
    for i in range(len(raw)//size):
        r=raw[i*size:(i+1)*size]
        result.append(dict(id=i,name=r[:21].split(b'\0')[0].decode('cp1361'),icon=struct.unpack_from('<H',r,21)[0],
            category=r[34] if size==84 else r[23],allowed=r[23] if size==84 else 0,
            stats=list(struct.unpack_from('<6h',r,35)) if size==84 else [],
            effects=list(struct.unpack_from('<8h',r,35)) if size==84 else [],
            description='' if size==84 else r[28:].split(b'\0')[0].decode('cp1361')))
    return result


def extract(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    directory=output_root/'assets/ui/equipment';directory.mkdir(parents=True,exist_ok=True)
    sources={}
    def read(name):
        raw=(game_dir/name).read_bytes();sources[name]=hashlib.sha256(raw).hexdigest();return raw
    weapons=records(read('DATA/WEAPON.LST'),84);items=records(read('DATA/ITEM.LST'),58)
    palette=read('DATA/PROTOCOS.PAL');fxp=read('GRP/BACK.FXP');start=read('DATA/START.DAT')
    for name in ('GRP/IMENU.IMG','GRP/COMMON.IMG','GRP/PEOPLE.SPR','GRP/PEOPLE.SDA','HANGUL.FNT','ENGLISH.FNT'):read(name)
    ref=EquipmentReference(exe);ref.prepare(game_dir,start)
    renderer=OriginalSpriteRenderer(exe);verified=0;images={}
    wanted={'imenu-'+str(i) for i in (70,96,231,232,233,*range(17,53),*range(236,239))}
    wanted|={'common-'+str(r['icon']) for r in weapons+items};wanted.add('kaman-6')
    for name in sorted(wanted):
        raw=ref.raw[name];frame=decode_img_frame(raw);image=frame.to_image(palette)
        native=renderer.render(struct.pack('<HH',frame.width,frame.height)+raw,palette)
        if image.tobytes()!=native.tobytes():raise ValueError(f'원작 메뉴 그림 불일치: {name}')
        verified+=frame.width*frame.height;images[name]=image
        if name.startswith(('imenu-','common-')):
            ids=bytes(fxp[1024+c] for c in frame.indices)
            muted=frame.__class__(frame.width,frame.height,ids,frame.alpha).to_image(palette)
            images[name+'-muted']=muted
    # 선택 띠는 원작 0x2CF9D가 사용하는 현재 색 번호 변환표 4를 적용한다.
    bg=images['imenu-96'].copy();bg.alpha_composite(images['imenu-232'])
    lookup={tuple(v*4 for v in palette[i*3:i*3+3]):i for i in range(256)}
    shaded=bg.copy()
    for y in range(200):
        for x in range(320):
            c=fxp[1024+lookup[bg.getpixel((x,y))[:3]]]
            shaded.putpixel((x,y),tuple(v*4 for v in palette[c*3:c*3+3])+(255,))
    images['weapon-highlight']=shaded
    bg=images['imenu-96'].copy();bg.alpha_composite(images['imenu-231'])
    for y in range(200):
        for x in range(320):
            c=fxp[1024+lookup[bg.getpixel((x,y))[:3]]]
            bg.putpixel((x,y),tuple(v*4 for v in palette[c*3:c*3+3])+(255,))
    images['item-highlight']=bg
    (_,code),(_,data)=objects(exe);font=read('HANGUL.FNT');english=read('ENGLISH.FNT');tables=code[0x4040:0x4108]
    empty_weapon=bytes(ref.cpu.mem_read(0x704ac,40)).split(b'\0')[0].decode('cp1361')
    empty_items=bytes(ref.cpu.mem_read(0x70544,40)).split(b'\0')[0].decode('cp1361')
    text=''.join(r['name']+r['description'] for r in weapons+items)+empty_weapon+empty_items+'소지품장비'
    chars=sorted(set(text)-{'/','\n'});reference=OpeningReference(code,data,font)
    for c in chars:
        im=glyph(c,font,english,tables)
        if len(c.encode('cp1361'))==2:
            bits=reference.glyph(c.encode('cp1361'))
            if im.getchannel('A').tobytes()!=bytes(255 if bits[y*2+x//8]&(128>>(x%8)) else 0 for y in range(16) for x in range(16)):raise ValueError(c)
        images['glyph-'+str(ord(c))]=im
    atlas=Image.new('RGBA',(1024,4096));rects={};x=y=row=0
    for name,im in images.items():
        if x+im.width>1024:x=0;y+=row;row=0
        atlas.alpha_composite(im,(x,y));rects[name]=[x,y,im.width,im.height];x+=im.width;row=max(row,im.height)
    atlas.crop((0,0,1024,y+row)).save(directory/'atlas.png')
    eligible=[]
    raw=bytearray(start);raw[162:337]=bytes([1])*175;ref.prepare(game_dir,raw);eligible=ref.candidates()
    trace=ref.invoke(0x156f8,0,2)
    result=dict(weapons=weapons,items=items,rects=rects,palette=[v*4 for v in palette],sources=sources,
        native_sprite_pixels=verified,eligible=eligible,stat_layout=[e for e in trace if e['kind']=='number'],empty_weapon=empty_weapon,empty_items=empty_items)
    (directory/'equipment.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    cases=[]
    for n,item in enumerate(eligible):
        for variant in range(3):
            raw=bytearray(start);raw[162+item]=1+variant;raw[475]=255 if variant==0 else (0 if variant==1 else 1)
            if variant==1:raw[500]=1;raw[628]=3
            if variant==2:
                raw[162]=255;raw[163]=99
                for j in range(6):struct.pack_into('<H',raw,360+j*4,60000+j*3)
                raw[477],raw[478]=[(142,144),(145,154),(152,153),(160,142),(163,174)][n%5]
            ref.prepare(game_dir,raw);ref.begin_weapon();detached=ref.saved();candidates=ref.candidates()
            preview=ref.invoke(0x15808,0,2,item)
            ref.equip(item);after=ref.saved()
            cases.append(dict(item=item,variant=variant,before=base64.b64encode(raw).decode(),detached=base64.b64encode(detached).decode(),
                after=base64.b64encode(after).decode(),candidates=candidates,preview=[int(e['text']) for e in preview]))
    rejections=[]
    for item in [r['id'] for r in weapons if r['allowed'] and r['category']==5]:
        raw=bytearray(start);raw[162+item]=1;raw[476]=90
        ref.prepare(game_dir,raw);ref.begin_weapon();before=ref.saved();events=ref.equip(item)
        rejections.append(dict(item=item,before=base64.b64encode(before).decode(),after=base64.b64encode(ref.saved()).decode(),events=events))
    detached_cases=[]
    for count in (0,99,255):
        raw=bytearray(start);raw[475]=0;raw[162]=count;ref.prepare(game_dir,raw);ref.begin_weapon()
        detached_cases.append(dict(before=base64.b64encode(raw).decode(),after=base64.b64encode(ref.saved()).decode()))
    report=dict(cases=cases,rejections=rejections,detached_cases=detached_cases,sources=sources)
    (output_root/'docs/equipment-native-verification.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('장비 추출:',len(cases),'상태,',verified,'픽셀,',len(chars),'글자')
    return result,report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();extract(a.game_dir,a.output_root)
