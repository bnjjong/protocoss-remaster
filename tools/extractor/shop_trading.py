"""엘가임 잡화점의 원작 가격·입력·거래 상태와 그리기 명령을 추출한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_ECX
from equipment_reference import EquipmentReference
from decode_title import decode_img_frame
from decode_opening import glyph
from verify_original_map import unpack_game
from verify_original_title_save import objects
from verify_original_user_sprite import OriginalSpriteRenderer
from decode_equipment import records
from PIL import Image

class ShopReference(EquipmentReference):
    def prepare(self,game_dir,source):
        self.signed_numbers=True
        super().prepare(game_dir,source)
        raw=(game_dir/'DATA/ITEM_P.GUP').read_bytes();offset,length=struct.unpack_from('<II',raw)
        if length!=156:raise ValueError('잡화점 가격 표 크기 불일치')
        self.cpu.mem_write(0x350000,raw[offset:offset+length]);self.dword(0x1640,0x350000)
        self.dword(0xe558,0);self.word(0xeb0e,0);self.byte(0xf1a3,1)

    def invoke(self,address,eax=0,edx=0,ebx=0,keys=(),ecx=0):
        def hook(c,a,size,user):
            if a in (0x10660,0x29b1c):self.return_from(c)
        self.cpu.reg_write(UC_X86_REG_ECX,ecx)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:return super().invoke(address,eax,edx,ebx,keys)
        finally:self.cpu.hook_del(token)

    def quantity(self,item,price,sell,keys):
        return self.invoke(0x28cf8 if sell else 0x27ee4,item,price,0,keys,ecx=0 if sell else 1)

    def catalog(self,sell=False):
        self.invoke(0x28408 if sell else 0x2834c)
        count=struct.unpack('<I',self.cpu.mem_read(0x7e550,4))[0]
        return [struct.unpack('<HI',self.cpu.mem_read(0x7e610+i*6,6)) for i in range(count)]

    def amount_frame(self,item,price,quantity,sell,frame=None):
        # 원작 호출 규약의 세 스택 인자는 작은 CALL 래퍼로 전달한다.
        wrapper=b'\x68'+struct.pack('<I',0 if sell else 1)+b'\x6a\x00\x68'+struct.pack('<I',int(sell))
        wrapper+=b'\xb8'+struct.pack('<I',item)+b'\xba'+struct.pack('<I',price)+b'\xbb'+struct.pack('<I',quantity)+b'\xb9'+struct.pack('<I',1 if frame is None else 0)
        wrapper+=b'\xe8'+struct.pack('<i',0x29590-(0x3e0000+len(wrapper)+5))+b'\xc3'
        self.cpu.mem_write(0x3e0000,wrapper)
        events=self.invoke(0x3e0000)
        if frame is None:return events
        starts=[i for i,e in enumerate(events) if e["kind"]=="number" and e["y"]==26]+[len(events)]
        return events[starts[frame]:starts[frame+1]]

    def volumes(self):
        self.dword(0x1644,127);levels=[]
        def hook(c,a,size,user):
            if a==0x66368:levels.append(c.reg_read(UC_X86_REG_EAX));self.return_from(c)
            elif a in (0x1f108,0x243f0):self.return_from(c,4 if a==0x1f108 else 0)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:self.invoke(0x28700);self.invoke(0x28618)
        finally:self.cpu.hook_del(token)
        return levels


def extract(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    ref=ShopReference(exe);start=(game_dir/'DATA/START.DAT').read_bytes();ref.prepare(game_dir,start)
    raw=(game_dir/'DATA/ITEM_P.GUP').read_bytes();offset,length=struct.unpack_from('<II',raw)
    prices=[dict(id=i,buy=b,sell=s) for i,(b,s) in enumerate(struct.iter_unpack('<HH',raw[offset:offset+length]))]
    sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('DATA/ITEM_P.GUP','DATA/ITEM.LST','GRP/IMENU.IMG','GRP/COMMON.IMG','DATA/PROTOCOS.PAL','GRP/BACK.FXP','HANGUL.FNT','ENGLISH.FNT')}
    directory=output_root/'assets/ui/shop';directory.mkdir(parents=True,exist_ok=True)
    palette=(game_dir/'DATA/PROTOCOS.PAL').read_bytes();fxp=(game_dir/'GRP/BACK.FXP').read_bytes()
    items=records((game_dir/'DATA/ITEM.LST').read_bytes(),58);images={};verified=0
    renderer=OriginalSpriteRenderer(exe)
    wanted={f'imenu-{i}' for i in range(65)}|{f'common-{r["icon"]}' for r in items}
    for name in sorted(wanted):
        f=decode_img_frame(ref.raw[name]);im=f.to_image(palette)
        native=renderer.render(struct.pack('<HH',f.width,f.height)+ref.raw[name],palette)
        if native.tobytes()!=im.tobytes():raise ValueError(name)
        verified+=f.width*f.height;images[name]=im
        ids=bytes(fxp[1024+c] for c in f.indices)
        images[name+'-muted']=f.__class__(f.width,f.height,ids,f.alpha).to_image(palette)
    (_,code),(_,data)=objects(exe);font=(game_dir/'HANGUL.FNT').read_bytes();english=(game_dir/'ENGLISH.FNT').read_bytes()
    for c in sorted(set(''.join(i['name']+i['description'] for i in items)+'-')):images['glyph-'+str(ord(c))]=glyph(c,font,english,code[0x4040:0x4108])
    atlas=Image.new('RGBA',(1024,4096));rects={};x=y=row=0
    for name,im in images.items():
        if x+im.width>1024:x=0;y+=row;row=0
        atlas.alpha_composite(im,(x,y));rects[name]=[x,y,im.width,im.height];x+=im.width;row=max(row,im.height)
    atlas.crop((0,0,1024,y+row)).save(directory/'atlas.png')
    data=dict(prices=prices,items=items,rects=rects,palette=[v*4 for v in palette],shade=list(fxp[1024:1280]),sources=sources,native_sprite_pixels=verified)
    (directory/'shop.json').write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n')
    cases=[]
    def case(name,item,sell,money,owned,keys):
        source=bytearray(start);source[123:162]=bytes(39);source[123+item]=owned
        struct.pack_into('<i',source,119,money);ref.prepare(game_dir,source)
        price=prices[item]['sell' if sell else 'buy'];events=ref.quantity(item,price,sell,keys)
        cases.append(dict(name=name,item=item,sell=sell,price=price,keys=keys,before=base64.b64encode(source).decode(),after=base64.b64encode(ref.saved()).decode(),effects=[e['id'] for e in events if e['kind']=='effect']))
    for p in prices:
        if p['buy']:
            for label,money,owned,keys in [('one',10000,0,[28]),('poor',p['buy']-1,0,[77,28]),('exact',p['buy'],0,[77,28]),('zero',10000,0,[75,28]),('cancel',10000,0,[72,1]),('ten',10000,2,[72,80,77,28]),('cap',1000000,90,[72,28]),('native-overdraft',0,90,[72,28]),('full',10000,99,[72,77,28]),('byte255',10000,255,[72,28])]:
                case(f'buy-{p["id"]}-{label}',p['id'],False,money,owned,keys)
        for owned in (1,99,255):case(f'sell-{p["id"]}-{owned}',p['id'],True,200,owned,[72,72,77,80,75,28])
    for label,keys in [('zero',[75,28]),('cancel',[72,1]),('all',[72]*26+[28]),('space',[57,1])]:case('sell-'+label,3,True,0,255,keys)
    case('sell-overflow',31,True,2147483640,1,[28])
    case('buy-space',0,False,100,0,[57,1])
    lists=[]
    for label,qty in [('empty',[0]*39),('all',[1]*39),('mixed',[i%3 for i in range(39)])]:
        source=bytearray(start);source[123:162]=bytes(qty);ref.prepare(game_dir,source)
        lists.append(dict(name=label,before=base64.b64encode(source).decode(),buy=ref.catalog(),sell=ref.catalog(True)))
    homes=[]
    for keys in ([28],[72,28],[80,28],[72,72,28],[57],[1]):
        ref.prepare(game_dir,start);events=ref.invoke(0x28c18,keys=keys)
        homes.append(dict(keys=keys,choice=struct.unpack('<i',ref.cpu.mem_read(0x7eb0e,2)+b'\0\0')[0],result=ref.cpu.reg_read(UC_X86_REG_EAX),effects=[e['id'] for e in events if e['kind']=='effect']))
    report=dict(cases=cases,lists=lists,homes=homes,volumes=ref.volumes(),sources=sources)
    (output_root/'docs/shop-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('잡화점 추출:',len(cases),'거래,',verified,'그림 픽셀')
    return data,report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    a=p.parse_args();extract(a.game_dir,a.output_root)
