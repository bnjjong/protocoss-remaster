"""실제 무기·소지품 메뉴를 원작 그리기 함수와 대조한다."""
import argparse,base64,json,struct
from pathlib import Path
from PIL import Image
from decode_title import decode_img_frame
from decode_opening import glyph
from decode_equipment import records
from equipment_reference import EquipmentReference
from verify_original_map import unpack_game
from verify_original_title_save import objects
from verify_original_user_sprite import OriginalSpriteRenderer


def verify(game_dir,output_root,executable=None,report_name='equipment-render-verification.json'):
    exe=executable if executable is not None else unpack_game(game_dir)
    folder=output_root/'desktop/build/reports/equipment';scenes=json.loads((folder/'scenes.json').read_text());results=[]
    ref=EquipmentReference(exe);renderer=OriginalSpriteRenderer(exe);palette=(game_dir/'DATA/PROTOCOS.PAL').read_bytes()
    fxp=(game_dir/'GRP/BACK.FXP').read_bytes();(_,code),(_,data)=objects(exe)
    font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes();tables=code[0x4040:0x4108]
    weapons=records((game_dir/'DATA/WEAPON.LST').read_bytes(),84);items=records((game_dir/'DATA/ITEM.LST').read_bytes(),58)
    for scene in scenes:
        source=base64.b64decode(scene['save']);ref.prepare(game_dir,source);ref.byte(0x163c,0)
        images={};index_images={}
        def source_image(name,muted=False):
            key=(name,muted)
            if key not in images:
                raw=ref.raw[name];f=decode_img_frame(raw)
                image=renderer.render(struct.pack('<HH',f.width,f.height)+raw,palette)
                if muted:
                    ids=bytes(fxp[1024+c] for c in f.indices)
                    image=f.__class__(f.width,f.height,ids,f.alpha).to_image(palette)
                images[key]=image;index_images[name]=f
            return images[key]
        expected=source_image('imenu-96').copy()
        expected.alpha_composite(source_image('imenu-232' if scene['scene']=='WEAPON' else 'imenu-231'))
        # 도형의 색 번호 변환은 원작 BACK.FXP의 4번 표를 사용한다.
        ids=bytearray(index_images['imenu-96'].indices)
        overlay=index_images['imenu-232' if scene['scene']=='WEAPON' else 'imenu-231']
        for i,a in enumerate(overlay.alpha):
            if a:ids[i]=overlay.indices[i]
        def rect(x,y,width,height):
            for py in range(y,y+height):
                for px in range(x,x+width):
                    c=fxp[1024+ids[py*320+px]];expected.putpixel((px,py),tuple(v*4 for v in palette[c*3:c*3+3])+(255,))
        def text(value,x,y,color=211):
            left=x
            for ch in value:
                if ch in '/\n':x=left;y+=16;continue
                if x>=320:break
                im=glyph(ch,font,english,tables);alpha=im.getchannel('A')
                ink=Image.new('RGBA',im.size,(0,0,0,255));ink.putalpha(alpha);expected.alpha_composite(ink,(x+1,y+1))
                ink=Image.new('RGBA',im.size,tuple(v*4 for v in palette[color*3:color*3+3])+(255,));ink.putalpha(alpha)
                expected.alpha_composite(ink,(x,y));x+=im.width
        def number(value,x,y,right=True,muted=False):
            s=str(value)
            if right:x-=len(s)*8
            for ch in s:expected.alpha_composite(source_image('imenu-'+str(int(ch)+17),muted),(x,y));x+=8
        def draw(events):
            for e in events:
                if e['kind']=='image':expected.alpha_composite(source_image(e['image'],e.get('muted',False)),(e['x'],e['y']))
                elif e['kind']=='rect':rect(e['x'],e['y'],e['width'],e['height'])
                elif e['kind']=='text':text(e['text'],e['x'],e['y'],e['color'])
                elif e['kind']=='number':number(e['text'],e['x'],e['y'],e['right'],e['color']==1)
        if scene['scene']=='WEAPON':
            character=scene.get('character',0);slot=scene.get('slot',2);index=ref.party.index(character)
            if scene['stage']=='HOME':draw(ref.invoke(0x151b8,0,index))
            else:
                draw(ref.invoke(0x156f8,index,slot))
                if scene['stage']=='SELECT':
                    choices=ref.candidates(character,slot)
                    if choices:draw(ref.invoke(0x15808,character,slot,choices[scene['selected']]))
                    draw(ref.invoke(0x15518,scene['scroll'],scene['selected']-scene['scroll']))
        else:
            equipment=scene['inventory_equipment'];text('장비' if equipment else '소지품',14,16)
            if not equipment:
                ref.invoke(0x28408,0);draw(ref.invoke(0x19930,scene['inventory_scroll'],scene['inventory_selected']-scene['inventory_scroll']))
            else:
                # 장비 소지품 조회는 ITEM의 여섯 줄 배치에 전체 장비를 읽기 전용으로 표시한다.
                rows=[r for r in weapons if source[162+r['id']]>0]
                if not rows:text(bytes(ref.cpu.mem_read(0x70544,40)).split(b'\0')[0].decode('cp1361'),24,169)
                else:
                    row=scene['inventory_selected']-scene['inventory_scroll'];rect(32,37+row*18,258,10)
                    for i,r in enumerate(rows[scene['inventory_scroll']:scene['inventory_scroll']+6]):
                        y=34+i*18;expected.alpha_composite(source_image('common-'+str(r['icon'])),(44,y));text(r['name'],69,y)
                        number(source[162+r['id']],269,y+3)
        expected.save(folder/(scene['name']+'-native.png'))
        actual=Image.open(folder/(scene['name']+'.png')).convert('RGBA').resize((320,200),Image.Resampling.NEAREST)
        diff=[(x,y) for y in range(200) for x in range(320) if expected.getpixel((x,y))!=actual.getpixel((x,y))]
        if diff:raise ValueError(f'메뉴 화면 불일치: {scene["name"]}, {len(diff)}픽셀, 처음 {diff[:5]}')
        results.append(dict(name=scene['name'],pixels=64000,native_function='0x151B8/0x156F8/0x15808/0x15518' if scene['scene']=='WEAPON' else '0x19930' if not scene['inventory_equipment'] else '소지품 배치·원작 그림 조합'))
    report=dict(scenes=results,compared_pixels=len(results)*64000)
    (output_root/'docs'/report_name).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('장비 메뉴 화면 일치:',len(results),'장,',report['compared_pixels'],'픽셀')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    p.add_argument('--report-name',default='equipment-render-verification.json')
    a=p.parse_args();verify(a.game_dir,a.output_root,report_name=a.report_name)
