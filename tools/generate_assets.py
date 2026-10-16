# -*- coding: utf-8 -*-
"""Original tabletop item art and native Java 1.21.1 models.

Run with Python + Pillow + NumPy. All output paths are rooted at this project;
this generator writes only assets/tabletop, art, and docs/assets.md.
"""
from __future__ import annotations

import json
import hashlib
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageEnhance
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/tabletop"
TEX = ASSETS / "textures/item"
MODELS = ASSETS / "models/item"
BLOCK_MODELS = ASSETS / "models/block"
BLOCK_STATES = ASSETS / "blockstates"
BLOCK_TEX = ASSETS / "textures/block"
ART = ROOT / "art"
for directory in (TEX, MODELS, BLOCK_MODELS, BLOCK_STATES, BLOCK_TEX, ART, ASSETS / "lang", ROOT / "docs"):
    directory.mkdir(parents=True, exist_ok=True)

# Freeze the previously delivered assets before the first 0.3 resource run.
# Existing game screenshots and earlier software previews remain historical evidence.
V3_BASELINE = ART / "assets-v3-baseline.json"
if not V3_BASELINE.exists():
    V3_BASELINE.write_text(json.dumps({
        "assets": {p.relative_to(ASSETS).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in sorted(ASSETS.rglob("*")) if p.is_file()},
        "art_png": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(ART.glob("*.png"))},
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

INK = "#13232a"
PAPER = "#eee4c8"
GOLD = "#d9b771"
CATS = {
    "townsfolk": ("#305d84", "#a4c7df", "TOWNSFOLK", "镇民"),
    "outsider": ("#3a5276", "#aab6d5", "OUTSIDER", "外来者"),
    "minion": ("#75384d", "#e3a4b1", "MINION", "爪牙"),
    "demon": ("#9c3238", "#f0a5a5", "DEMON", "恶魔"),
}
ROLES = [
    ("washerwoman", "Washerwoman", "洗衣妇", "townsfolk"),
    ("librarian", "Librarian", "图书管理员", "townsfolk"),
    ("investigator", "Investigator", "调查员", "townsfolk"),
    ("chef", "Chef", "厨师", "townsfolk"),
    ("empath", "Empath", "共情者", "townsfolk"),
    ("fortune_teller", "Fortune Teller", "占卜师", "townsfolk"),
    ("undertaker", "Undertaker", "送葬者", "townsfolk"),
    ("monk", "Monk", "僧侣", "townsfolk"),
    ("ravenkeeper", "Ravenkeeper", "守鸦人", "townsfolk"),
    ("virgin", "Virgin", "贞洁者", "townsfolk"),
    ("slayer", "Slayer", "猎手", "townsfolk"),
    ("soldier", "Soldier", "士兵", "townsfolk"),
    ("mayor", "Mayor", "镇长", "townsfolk"),
    ("butler", "Butler", "管家", "outsider"),
    ("drunk", "Drunk", "酒鬼", "outsider"),
    ("recluse", "Recluse", "陌客", "outsider"),
    ("saint", "Saint", "圣徒", "outsider"),
    ("poisoner", "Poisoner", "投毒者", "minion"),
    ("spy", "Spy", "间谍", "minion"),
    ("scarlet_woman", "Scarlet Woman", "红唇女郎", "minion"),
    ("baron", "Baron", "男爵", "minion"),
    ("imp", "Imp", "小恶魔", "demon"),
]


def font(size, bold=False):
    paths = [Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
             Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
             Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
    for path in paths:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def center(draw, text, y, size=12, fill=INK, bold=True, x=64):
    if x == 64:
        while size > 6 and draw.textlength(text, font=font(size, bold)) > 106:
            size -= 1
    draw.text((x, y), text, font=font(size, bold), anchor="mt", fill=fill)


def frame(base, trim=GOLD):
    image = Image.new("RGB", (128, 192), base)
    d = ImageDraw.Draw(image)
    d.rectangle((3, 3, 124, 188), outline=trim, width=2)
    d.rectangle((7, 7, 120, 184), outline=trim, width=1)
    for x in (12, 116):
        for y in (12, 180):
            d.polygon([(x, y - 3), (x + 3, y), (x, y + 3), (x - 3, y)], fill=trim)
    return image, d


def save_texture(name, image):
    image.save(TEX / f"{name}.png", optimize=True)


def make_back(name, caption, base="#1c343d", private=False):
    im, d = frame(base)
    # A project-original door-and-lantern motif, not the official game logo.
    d.rectangle((28, 43, 100, 143), outline=GOLD, width=2)
    d.arc((34, 47, 94, 109), 180, 360, fill=GOLD, width=3)
    d.line((34, 78, 34, 132), fill=GOLD, width=3)
    d.line((94, 78, 94, 132), fill=GOLD, width=3)
    d.rectangle((56, 77, 72, 113), outline=PAPER, width=2)
    d.line((64, 66, 64, 76), fill=PAPER, width=2)
    d.polygon([(64, 84), (68, 99), (64, 107), (60, 99)], fill=GOLD)
    d.line((40, 123, 88, 123), fill=GOLD, width=2)
    center(d, "身份保密" if private else "围桌桌游", 22, 14, PAPER)
    center(d, caption, 155, 10, PAPER)
    save_texture(name, im)


make_back("identity_card", "密封身份卡", "#322b43", True)
make_back("role_back", "角色参考牌", "#322b43", True)

def flip_face(color):
    """High contrast, original art-deco paper faces based on the user's cards."""
    im = Image.new("RGB", (512,768), "#f0e7d0");d=ImageDraw.Draw(im)
    rng = random.Random(707)
    for _ in range(10000):
        x,y=rng.randrange(512),rng.randrange(768)
        d.point((x,y),fill=rng.choice(("#e8dcc1","#f7efdb","#ede1c7")))
    d.rounded_rectangle((9,9,502,758),radius=28,outline="#f9f0dc",width=7)
    d.rounded_rectangle((17,17,494,750),radius=24,outline=color,width=5)
    d.rounded_rectangle((31,31,480,736),radius=19,outline="#ac9877",width=2)
    for x,y,sx,sy in ((43,43,1,1),(468,43,-1,1),(43,724,1,-1),(468,724,-1,-1)):
        d.line((x,y,x+sx*53,y,x+sx*53,y+sy*9),fill=color,width=3)
        d.line((x,y,x,y+sy*53,x+sx*9,y+sy*53),fill=color,width=3)
    # Fans stay outside the digit, so a seated camera can identify the number.
    for cx,sign in ((26,1),(485,-1)):
        for y in (274,306,348,384,420,462,494):
            dx=43 if y in (348,384,420) else 30
            d.line((cx,384,cx+sign*dx,y),fill=color,width=3)
        d.arc((cx-54,272,cx+54,496),-90 if sign>0 else 90,90 if sign>0 else 270,fill=color,width=3)
    return im,d


def flip_text(d,text,at,size,color,anchor="mm",stroke=0,max_width=404):
    face=font(size,True)
    while size>12 and d.textlength(text,font=face)>max_width:
        size-=1;face=font(size,True)
    d.text(at,text,font=face,anchor=anchor,fill=color,stroke_width=stroke,stroke_fill="#f7efdb")


def flip_corners(im, mark, color, underline=False):
    """Identical corner indices rotated 180 degrees for opposite seated players."""
    tile=Image.new("RGBA",(96,96),(0,0,0,0));td=ImageDraw.Draw(tile)
    flip_text(td,mark,(48,43),58 if len(mark)<3 else 44,color,max_width=91)
    if underline:
        td.line((28,80,68,80),fill=color,width=4)
    im.paste(tile,(39,39),tile)
    reverse=tile.transpose(Image.Transpose.ROTATE_180)
    im.paste(reverse,(377,633),reverse)


def flip_footer(d, lines, color):
    for i,line in enumerate(lines):
        flip_text(d,line,(256,582+i*35),27,color,max_width=410)


# A detailed two-way teal back, drawn independently of official printed artwork.
im=Image.new("RGB",(512,768),"#258f97");d=ImageDraw.Draw(im)
for y in range(768):
    d.line((0,y,511,y),fill=(28+(y%16)//4,137+(y%11)//3,145+(y%13)//3))
d.rounded_rectangle((10,10,501,757),radius=28,outline="#173e4c",width=8)
d.rounded_rectangle((21,21,490,746),radius=23,outline="#eed9a7",width=5)
d.rounded_rectangle((34,34,477,733),radius=18,outline="#d1bd85",width=2)
for cy in (142,626):
    for x,sign in ((40,1),(472,-1)):
        for i,col in enumerate(("#f1d67d","#e78662","#f6e6be","#204d5c")):
            d.polygon([(x,cy),(x+sign*65,cy-65+i*31),(x+sign*94,cy-48+i*31)],fill=col)
emblem=Image.new("RGBA",(310,324),(0,0,0,0));ed=ImageDraw.Draw(emblem)
flip_text(ed,"七连翻",(155,39),52,"#f8e9c8",max_width=292)
flip_text(ed,"7",(155,166),217,"#f8e9c8",stroke=3,max_width=235)
flip_text(ed,"FLIP SEVEN",(155,291),28,"#f8e9c8",max_width=291)
im.paste(emblem,(101,52),emblem)
reverse=emblem.transpose(Image.Transpose.ROTATE_180);im.paste(reverse,(101,392),reverse)
d=ImageDraw.Draw(im);d.line((75,384,437,384),fill="#edd39b",width=2)
save_texture("card_back",im)


for value in range(13):
    col = ["#ac684a", "#9f7936", "#ab4138", "#426983", "#518366", "#a3822c", "#527890", "#a93949", "#769b3d", "#4f765f", "#b07035", "#277795", "#635242"][value]
    im,d=flip_face(col)
    flip_text(d,str(value),(256,357),372 if value<10 else 278,col,stroke=3,max_width=401)
    if value in (6,9):
        d.line((218,550,294,550),fill=col,width=5)
    flip_corners(im,str(value),col,value in (6,9))
    words=["ZERO","ONE","TWO","THREE","FOUR","FIVE","SIX","SEVEN","EIGHT","NINE","TEN","ELEVEN","TWELVE"]
    d.rectangle((105,593,407,650),fill="#f7efdb",outline=col,width=2)
    flip_text(d,words[value],(256,620),37,col,max_width=288)
    save_texture(f"number_{value}", im)

for value in (2, 4, 6, 8, 10):
    im,d=flip_face("#9c702a")
    d.rounded_rectangle((62,172,450,514),radius=29,fill="#edc86a",outline="#9c702a",width=4)
    flip_text(d,"加分牌",(256,132),52,"#806025",max_width=270)
    flip_text(d,f"+{value}",(256,338),230 if value<10 else 180,"#7c4d27",stroke=3,max_width=371)
    flip_corners(im,f"+{value}","#806025")
    flip_footer(d,(f"本轮额外加 {value} 分","不参与 ×2 倍率"),"#806025")
    save_texture(f"plus_{value}",im)

SPECIALS = {
    "times_two": ("倍率牌", "x2", "数字牌得分翻倍", "#efbd53", "#7b432f"),
    "freeze": ("冻结", "停手", "选择一名玩家", "#415f8b", "#c0d5e7"),
    "flip_three": ("翻三张", "3", "连续翻出三张牌", "#dfbb57", "#5f4937"),
    "second_chance": ("第二次机会", "保留", "留住一次机会", "#ab353c", "#f1dec7"),
}
for name, (title, mark, footer, base, trim) in SPECIALS.items():
    col={"times_two":"#956022","freeze":"#356d93","flip_three":"#ba8631","second_chance":"#a33d4c"}[name]
    im,d=flip_face(col)
    flip_text(d,title,(256,136),47,col,max_width=320)
    d.ellipse((89,187,423,521),fill=col)
    flip_corners(im,{"times_two":"×2","freeze":"停","flip_three":"3","second_chance":"1"}[name],col)
    if name == "freeze":
        for angle in range(0, 360, 60):
            a = math.radians(angle)
            d.line((256,354,256+int(131*math.cos(a)),354+int(131*math.sin(a))),fill="#f7efdb",width=11)
            tip=(256+int(94*math.cos(a)),354+int(94*math.sin(a)))
            for turn in (-45,45):
                b=a+math.radians(180+turn)
                d.line((*tip,tip[0]+int(41*math.cos(b)),tip[1]+int(41*math.sin(b))),fill="#f7efdb",width=9)
        d.ellipse((235,333,277,375),fill="#f7efdb")
    elif name == "flip_three":
        for x,y in ((143,226),(186,263),(229,300)):
            d.rounded_rectangle((x,y,x+124,y+160),radius=11,fill=col,outline="#f7efdb",width=7)
        flip_text(d,"3",(291,380),105,"#f7efdb",max_width=110)
    elif name == "second_chance":
        d.arc((149,245,367,463),-80,225,fill="#f7efdb",width=19)
        d.polygon([(136,338),(179,303),(177,371)],fill="#f7efdb")
        flip_text(d,"1",(258,349),123,"#f7efdb",max_width=116)
    else:
        flip_text(d,"×2",(256,348),185,"#f7efdb",max_width=300)
    lines={
        "times_two":("仅数字牌合计翻倍","加分与七种奖励不翻倍"),
        "freeze":("选择仍在本轮的玩家","该玩家收手，保留本轮分数"),
        "flip_three":("选择仍在本轮的玩家","连翻3张，爆牌或七种数字即停","行动牌照常生效"),
        "second_chance":("抵消一次重复：重复牌与本牌弃置","多余转赠在轮且无保护的他人","无人可用则弃牌"),
    }[name]
    flip_footer(d,lines,col)
    save_texture(name,im)


def glyph(d, name, col):
    """Original small glyphs built from pixel primitives, no downloaded artwork."""
    if name in ("washerwoman", "butler", "poisoner", "drunk"):
        if name == "washerwoman":
            d.polygon([(39, 79), (89, 79), (83, 111), (45, 111)], outline=col, width=3)
            for y in (86, 92, 98):
                d.line((47, y, 80, y), fill=col, width=2)
            d.rectangle((57, 63, 76, 86), fill=col)
        elif name == "butler":
            d.line((34, 106, 94, 106), fill=col, width=3)
            d.arc((41, 71, 87, 118), 180, 360, fill=col, width=3)
            d.rectangle((59, 64, 69, 69), fill=col)
        elif name == "poisoner":
            d.rectangle((56, 65, 72, 76), outline=col, width=3)
            d.polygon([(54, 76), (40, 91), (40, 114), (88, 114), (88, 91), (74, 76)], outline=col, width=3)
            d.line((44, 99, 84, 99), fill=col, width=3)
            d.rectangle((61, 85, 67, 92), fill=col)
        else:
            d.polygon([(42, 70), (86, 70), (79, 98), (64, 104), (49, 98)], outline=col, width=3)
            d.line((64, 104, 64, 117), fill=col, width=3)
            d.line((50, 119, 78, 119), fill=col, width=3)
    elif name in ("librarian", "undertaker", "recluse", "saint"):
        if name == "librarian":
            d.polygon([(31, 68), (57, 68), (64, 75), (71, 68), (97, 68), (97, 113), (71, 113), (64, 119), (57, 113), (31, 113)], outline=col, width=3)
            d.line((64, 76, 64, 111), fill=col, width=2)
            for y in (80, 89, 98):
                d.line((37, y, 55, y), fill=col, width=1)
                d.line((73, y, 91, y), fill=col, width=1)
        elif name == "undertaker":
            d.polygon([(50, 62), (78, 62), (91, 80), (80, 118), (48, 118), (37, 80)], outline=col, width=3)
            d.line((64, 77, 64, 105), fill=col, width=3)
            d.line((53, 86, 75, 86), fill=col, width=3)
        elif name == "recluse":
            d.polygon([(32, 86), (64, 62), (96, 86)], outline=col, width=3)
            d.rectangle((39, 85, 89, 116), outline=col, width=3)
            d.rectangle((58, 97, 71, 116), fill=col)
        else:
            d.ellipse((38, 66, 90, 80), outline=col, width=3)
            d.ellipse((52, 85, 76, 108), outline=col, width=3)
            d.line((40, 115, 88, 115), fill=col, width=3)
    elif name in ("investigator", "spy", "fortune_teller", "empath"):
        if name == "investigator":
            d.ellipse((34, 62, 76, 104), outline=col, width=4)
            d.line((72, 102, 94, 121), fill=col, width=5)
        elif name == "spy":
            d.polygon([(28, 93), (44, 77), (84, 77), (100, 93), (84, 110), (44, 110)], outline=col, width=3)
            d.ellipse((55, 83, 73, 101), fill=col)
        elif name == "fortune_teller":
            d.ellipse((39, 66, 89, 111), outline=col, width=3)
            d.polygon([(46, 115), (82, 115), (88, 120), (40, 120)], fill=col)
            d.line((64, 76, 64, 98), fill=col, width=2)
            d.line((54, 87, 74, 87), fill=col, width=2)
        else:
            d.polygon([(64, 116), (34, 86), (40, 69), (54, 68), (64, 79), (74, 68), (88, 69), (94, 86)], outline=col, width=3)
            d.line((41, 95, 54, 95, 60, 85, 68, 102, 75, 95, 87, 95), fill=col, width=2)
    elif name == "chef":
        d.ellipse((38, 65, 64, 88), outline=col, width=3)
        d.ellipse((52, 60, 79, 86), outline=col, width=3)
        d.ellipse((68, 65, 91, 88), outline=col, width=3)
        d.rectangle((45, 83, 84, 110), fill=PAPER, outline=col, width=3)
        d.line((47, 116, 82, 116), fill=col, width=3)
    elif name == "monk":
        d.polygon([(64, 62), (89, 82), (95, 118), (33, 118), (39, 82)], outline=col, width=3)
        d.ellipse((50, 77, 78, 102), outline=col, width=3)
        d.line((53, 109, 75, 109), fill=col, width=3)
    elif name == "ravenkeeper":
        d.polygon([(35, 104), (47, 80), (70, 81), (79, 68), (91, 68), (92, 82), (104, 86), (91, 91), (85, 110), (61, 113)], fill=col)
        d.rectangle((84, 75, 87, 78), fill=PAPER)
        d.line((62, 111, 57, 121), fill=col, width=3)
    elif name == "virgin":
        for x, y in ((53, 78), (75, 78), (45, 95), (83, 95), (64, 106)):
            d.ellipse((x - 9, y - 9, x + 9, y + 9), outline=col, width=3)
        d.ellipse((56, 87, 72, 102), fill=col)
        d.line((64, 107, 64, 124), fill=col, width=3)
    elif name in ("slayer", "soldier"):
        if name == "slayer":
            d.polygon([(42, 110), (46, 92), (81, 61), (89, 70), (58, 107)], outline=col, width=3)
            d.line((42, 100, 61, 119), fill=col, width=4)
            d.line((39, 117, 46, 110), fill=col, width=4)
        else:
            d.polygon([(64, 61), (91, 71), (86, 105), (64, 121), (42, 105), (37, 71)], outline=col, width=3)
            d.line((64, 71, 64, 109), fill=col, width=3)
            d.line((48, 89, 80, 89), fill=col, width=3)
    elif name in ("mayor", "baron"):
        d.polygon([(35, 76), (49, 90), (64, 66), (79, 90), (93, 76), (86, 108), (42, 108)], outline=col, width=3)
        d.rectangle((43, 113, 85, 118), fill=col)
        if name == "baron":
            d.line((64, 88, 64, 103), fill=col, width=3)
    elif name == "scarlet_woman":
        d.polygon([(64, 66), (85, 74), (93, 94), (83, 113), (64, 122), (45, 113), (35, 94), (43, 74)], outline=col, width=3)
        d.polygon([(64, 77), (78, 85), (78, 103), (64, 112), (50, 103), (50, 85)], outline=col, width=3)
        d.rectangle((59, 88, 69, 100), fill=col)
    else:  # imp
        d.polygon([(43, 84), (38, 62), (57, 78), (71, 78), (90, 62), (85, 84), (89, 105), (64, 122), (39, 105)], outline=col, width=3)
        d.rectangle((48, 91, 56, 94), fill=col)
        d.rectangle((72, 91, 80, 94), fill=col)
        d.line((57, 109, 71, 109), fill=col, width=3)


for rid, title, cn, category in ROLES:
    base, light, caption, category_cn = CATS[category]
    im, d = frame(PAPER, base)
    d.rectangle((8, 8, 119, 36), fill=base)
    center(d, category_cn, 14, 14, PAPER)
    d.ellipse((21, 49, 107, 133), outline=base, width=2)
    glyph(d, rid, base)
    center(d, cn, 141, 17, base)
    center(d, title.upper(), 164, 8, base)
    center(d, "暗流涌动", 178, 8, base)
    save_texture(f"role_{rid}", im)

for name, title, subtitle in (("script_card", "暗流涌动", "剧本参考"), ("rules_card", "游戏规则", "参考卡片")):
    im, d = frame(PAPER, "#47364e")
    center(d, title, 17, 18, "#47364e")
    center(d, subtitle, 38, 13, "#47364e")
    for i, cat in enumerate(CATS):
        base, light, label, _ = CATS[cat]
        y = 68 + i * 23
        d.rectangle((17, y, 28, y + 11), fill=base)
        d.rectangle((36, y + 1, 108, y + 3), fill=base)
        d.rectangle((36, y + 7, 89, y + 9), fill="#a59b89")
    center(d, "手持阅读", 166, 11, "#47364e")
    save_texture(name, im)

# Phone textures are a visual shell. Runtime messages are rendered by the client.
im, d = frame("#162832", "#608a91")
d.rectangle((9, 13, 118, 178), fill="#24414c")
d.rectangle((39, 5, 86, 9), fill="#060f16")
center(d, "桌间手机", 20, 14, "#e4ead9")
center(d, "消息", 42, 10, "#9bc6c4")
d.rectangle((18, 62, 88, 84), fill="#bed6c6")
d.rectangle((43, 94, 109, 116), fill="#5d9d9b")
d.rectangle((18, 125, 87, 147), fill="#bed6c6")
for y, x in ((71, 28), (103, 53), (134, 28)):
    for k in range(3):
        d.rectangle((x + 7 * k, y, x + 7 * k + 2, y + 2), fill="#24414c")
d.rectangle((21, 157, 98, 166), outline="#789898", width=1)
d.polygon([(103, 157), (113, 162), (103, 166)], fill="#bad1c1")
d.rectangle((48, 183, 80, 185), fill="#acc3be")
save_texture("phone", im)
im, d = frame("#273b43", "#57717c")
d.rectangle((12, 16, 39, 58), fill="#182830")
for y in (26, 45):
    d.ellipse((19, y - 5, 31, y + 7), fill="#739494", outline="#0c1822", width=2)
d.rectangle((17, 145, 110, 147), fill="#50737a")
center(d, "桌间", 157, 13, "#99b8b4")
save_texture("phone_back", im)

im, d = frame("#332736")
rng = random.Random(1211)
for _ in range(900):
    x, y = rng.randrange(10, 118), rng.randrange(10, 182)
    d.point((x, y), fill=rng.choice(("#3b2e3c", "#302334", "#423440")))
d.rectangle((15, 17, 112, 174), outline=GOLD, width=2)
center(d, "说书人的", 26, 13, GOLD)
center(d, "魔典", 45, 19, GOLD)
d.ellipse((31, 73, 97, 139), outline=GOLD, width=3)
d.ellipse((37, 79, 91, 133), outline="#91734e", width=1)
for a in range(12):
    angle = a * math.pi / 6
    x, y = 64 + int(25 * math.sin(angle)), 106 - int(25 * math.cos(angle))
    d.rectangle((x - 1, y - 1, x + 1, y + 1), fill=GOLD)
d.line((64, 106, 64, 89), fill=GOLD, width=3)
d.line((64, 106, 79, 115), fill=GOLD, width=3)
center(d, "主持辅助", 156, 11, GOLD)
save_texture("grimoire", im)
im, d = frame("#332736")
d.rectangle((21, 24, 108, 167), outline="#72563e", width=2)
save_texture("grimoire_back", im)

for name, color in (("card_edge", "#bfb59a"), ("phone_edge", "#334e58"), ("book_leather", "#332736"), ("clasp", GOLD)):
    im = Image.new("RGB", (16, 16), color)
    d = ImageDraw.Draw(im)
    d.line((0, 0, 15, 0), fill="#eee4c8" if name == "card_edge" else color, width=1)
    save_texture(name, im)
im = Image.new("RGB", (16, 16), PAPER)
d = ImageDraw.Draw(im)
for y in range(1, 16, 3):
    d.line((0, y, 15, y), fill="#b2a58c", width=1)
save_texture("book_pages", im)

# Physical-component revision: round character tokens and an open felt-lined box.
def paper(size, seed=34):
    im = Image.new("RGBA", size, PAPER)
    d = ImageDraw.Draw(im)
    randomizer = random.Random(seed)
    for _ in range(size[0]*size[1]//15):
        x, y = randomizer.randrange(size[0]), randomizer.randrange(size[1])
        d.point((x,y),fill=randomizer.choice(("#e8ddc1", "#ded1b4", "#f3e9d0")))
    return im


def circular_token(name, title, color, role=None, back=False):
    im = paper((128,128))
    alpha = Image.new("L", (128,128));ImageDraw.Draw(alpha).ellipse((2,2,125,125),fill=255)
    im.putalpha(alpha)
    d = ImageDraw.Draw(im)
    d.ellipse((3,3,124,124),outline="#8a785b",width=2)
    d.ellipse((8,8,119,119),outline="#c5b293",width=1)
    center(d,title,17,16,color)
    if role:
        icon = Image.new("RGB",(128,192),PAPER)
        glyph(ImageDraw.Draw(icon),role,color)
        icon=icon.crop((24,57,107,125)).resize((61,50),Image.Resampling.NEAREST)
        im.paste(icon,(34,43))
        center(d,"角色参考",103,9,color)
    else:
        d.ellipse((43,43,85,85),outline=color,width=3)
        d.line((64,50,64,66,76,74),fill=color,width=3)
        center(d,"密封保密" if back else "投票标记",102,10,color)
    save_texture(name,im)


for rid,title,cn,category in ROLES:
    circular_token(f"role_{rid}",cn,CATS[category][0],rid)
circular_token("identity_card","身份","#453c43",back=True)
circular_token("role_back","角色","#453c43",back=True)
circular_token("vote_token","投票","#453c43")

# A large player script sheet has a real list arrangement instead of icon cards.
for name,heading in (("script_card","暗流涌动"),("rules_card","规则参考")):
    sheet=paper((256,384));sd=ImageDraw.Draw(sheet)
    sd.rectangle((5,5,250,378),outline="#826746",width=2)
    sd.text((128,13),heading,font=font(24,True),anchor="mt",fill="#70353a")
    sd.text((128,44),"角色清单" if name=="script_card" else "白昼 · 夜晚 · 提名 · 投票",font=font(11),anchor="mt",fill=INK)
    if name=="script_card":
        y=68
        for category in CATS:
            col,light,en,label=CATS[category]
            group=[r for r in ROLES if r[3]==category];row_count=(len(group)+1)//2;height=row_count*23+8
            sd.rectangle((11,y,33,y+height),fill="#90413d")
            for k,char in enumerate(label):sd.text((22,y+height//2-len(label)*6+k*12),char,font=font(10,True),anchor="mt",fill=PAPER)
            for j,(rid,en,cn,cat) in enumerate(group):
                x=40+(j//row_count)*103;yy=y+5+(j%row_count)*23
                icon=Image.new("RGB",(128,192),PAPER);glyph(ImageDraw.Draw(icon),rid,col)
                icon=icon.crop((24,57,107,125)).resize((15,14),Image.Resampling.NEAREST)
                sheet.paste(icon,(x,yy+2))
                sd.text((x+20,yy),cn,font=font(9,True),fill=col)
                sd.line((x+20,yy+13,x+94,yy+13),fill="#b6a98f",width=1)
                sd.line((x+20,yy+17,x+82,yy+17),fill="#cabba2",width=1)
            y+=height
            sd.line((10,y,243,y),fill="#9a7857",width=1)
    else:
        for i,(title,explain) in enumerate((("白天讨论","交流、推理与公开声明"),("提名投票","依座位顺序轮流回应"),("处决与死亡","死亡后继续讨论，保留幽灵票"),("夜间行动","私密选择与说书人裁定"),("保密原则","身份、醉毒与私聊按权限展示"),("主持辅助","魔典收集回复并给出建议"))):
            y=76+i*45
            sd.text((18,y),title,font=font(14,True),fill="#385b7c")
            sd.text((18,y+21),explain,font=font(9),fill=INK)
    save_texture(name,sheet)

# Double-tray open grimoire, black felt and a separate night-order sheet.
grim=Image.new("RGB",(512,320),"#191a1b");gd=ImageDraw.Draw(grim)
felt_rng=random.Random(11407)
for _ in range(22000):
    x,y=felt_rng.randrange(512),felt_rng.randrange(320)
    n=felt_rng.randrange(4);gd.point((x,y),fill=(22+n,23+n,26+n))
for x in (0,260):
    gd.rectangle((x+5,5,x+246,314),outline="#513447",width=7)
    gd.rectangle((x+14,14,x+237,305),outline="#b9945e",width=2)
for i in range(10):
    a=(i*36-90)*math.pi/180;x=129+int(76*math.cos(a));y=157+int(110*math.sin(a))
    gd.ellipse((x-19,y-19,x+19,y+19),fill=PAPER,outline="#b4a487",width=2)
    gd.ellipse((x-8,y-8,x+8,y+8),outline="#607480",width=2)
    gd.line((x,y-5,x,y+5),fill="#607480",width=2)
gd.text((129,143),"座位",font=font(15,True),anchor="mt",fill="#8c8d88")
gd.text((129,164),"标记位",font=font(10),anchor="mt",fill="#6c7373")
night=paper((196,270));nd=ImageDraw.Draw(night)
nd.text((98,10),"暗流涌动",font=font(18,True),anchor="mt",fill="#76323f")
nd.text((98,37),"夜间顺序参考",font=font(12,True),anchor="mt",fill=INK)
night_names=["投毒者","洗衣妇","图书管理员","调查员","厨师","共情者","占卜师","管家","间谍"]
for i,name in enumerate(night_names):
    nd.ellipse((13,64+i*20,22,73+i*20),outline="#376486",width=1)
    nd.text((31,61+i*20),name,font=font(11),fill="#375775")
    nd.line((111,70+i*20,180,70+i*20),fill="#b5a78c",width=1)
grim.paste(night.convert("RGB"),(282,25));save_texture("grimoire",grim)
neutral=paper((128,128),58);nd=ImageDraw.Draw(neutral)
nd.ellipse((2,2,125,125),fill="#eee3c8",outline="#a18a62",width=3)
nd.ellipse((9,9,118,118),outline="#c7b795",width=2)
nd.ellipse((38,31,91,82),outline="#687f8b",width=4)
nd.line((64,39,64,59,77,69),fill="#687f8b",width=4)
nd.line((43,94,85,94),fill="#a18a62",width=3)
save_texture("seat_token",neutral)
leather=Image.new("RGB",(64,64),"#493145");ld=ImageDraw.Draw(leather)
for y in range(64):
    for x in range(64):
        n=(x*19+y*11)%5;leather.putpixel((x,y),(67+n,39+n,59+n))
for y in (3,60):
    ld.line((0,y,63,y),fill="#a38356",width=1)
    for x in range(2,64,6):ld.line((x,y+1,x+2,y+1),fill="#d1b782",width=1)
save_texture("grimoire_leather",leather)
flip_edge=Image.new("RGB",(64,128),"#e6dcc5");ed=ImageDraw.Draw(flip_edge)
for x in range(64):
    color="#faf3e2" if x<10 or x>53 else "#c6b797" if x in (12,13,49,50) else "#e3d7bc"
    ed.line((x,0,x,127),fill=color)
for y in range(0,128,7):
    ed.line((18,y,46,y),fill="#dfd1b4",width=1)
save_texture("flip_edge",flip_edge)
deck_edge=Image.new("RGB",(64,256),"#e8dfc8");ed=ImageDraw.Draw(deck_edge)
for x in range(0,64,8):
    ed.line((x,0,x,255),fill="#b8a98f",width=1)
    ed.line((x+1,0,x+1,255),fill="#faf3e2",width=2)
for y in range(5,256,17):
    ed.line((3,y,61,y),fill="#e3d7bc",width=1)
save_texture("deck_edge",deck_edge)

box=Image.new("RGB",(256,384),"#1b1b2d");bd=ImageDraw.Draw(box)
for y in range(384):
    bd.line((18,y,237,y),fill=(29+y//48,27+y//70,47+y//23))
for x in (8,247):
    bd.line((x,4,x,379),fill="#9b976a",width=2)
    bd.line((x+(-5 if x>100 else 5),7,x+(-5 if x>100 else 5),376),fill="#465849",width=4)
    for y in range(17,375,16):bd.polygon([(x,y),(x+(-5 if x>100 else 5),y+6),(x,y+12)],outline="#a1a27c",width=1)
# Newly drawn ornamental mask, distinct from the reference box's printed artwork.
mask="#5c3e71";light="#735083"
bd.polygon([(79,137),(69,106),(81,88),(99,108),(107,126),(149,126),(157,107),(176,89),(189,108),(177,139),(178,195),(154,226),(128,244),(101,226),(78,195)],outline=mask,width=4)
bd.arc((72,91,109,139),150,440,fill=mask,width=4);bd.arc((147,91,184,139),100,380,fill=mask,width=4)
bd.polygon([(91,151),(116,142),(119,158),(101,163)],outline=light,width=3)
bd.polygon([(165,151),(140,142),(137,158),(155,163)],outline=light,width=3)
bd.line((128,147,119,182,128,187,138,182),fill=mask,width=3)
bd.arc((102,187,154,221),195,345,fill=light,width=3)
bd.line((112,207,143,207),fill=mask,width=3)
for x in (89,167):
    for y in (173,183,193):bd.ellipse((x-3,y-3,x+3,y+3),outline=mask,width=2)
bd.ellipse((115,218,142,246),outline=mask,width=3)
bd.text((128,262),"染",font=font(52,True),anchor="mt",fill="#e3e1e0")
bd.text((128,327),"钟楼谜团",font=font(19,True),anchor="mt",fill="#d2ced1")
save_texture("clock_box",box)

shroud=Image.new("RGBA",(128,128),(0,0,0,0));sd=ImageDraw.Draw(shroud)
sd.rectangle((19,34,109,94),fill="#171a1d")
sd.polygon([(19,34),(109,34),(97,47),(29,46)],fill="#2a2d30")
sd.polygon([(109,34),(109,94),(92,83),(97,47)],fill="#101316")
sd.line((24,87,100,89),fill="#2b2e30",width=1)
save_texture("death_shroud",shroud)
save_texture("shroud_edge",Image.new("RGB",(16,16),"#15181b"))
wand=Image.new("RGB",(32,128),"#77573d");wd=ImageDraw.Draw(wand)
for x in range(2,32,6):
    wd.line((x,0,x+4,29,x-2,61,x+2,90,x,127),fill="#4e3829",width=2)
wd.ellipse((8,20,24,41),outline="#ae845b",width=2)
wd.ellipse((12,62,27,82),outline="#ae845b",width=2)
save_texture("storyteller_wand",wand)

# Smartphone revision: original layered enclosure, inspired by MCPhone's
# separation of physical layers, not its coordinates, atlas, code or apps.
screen=Image.new("RGB",(224,464));sd=ImageDraw.Draw(screen)
for y in range(464):sd.line((0,y,223,y),fill=(18+y//35,40+y//17,53+y//24))
sd.polygon([(0,290),(87,169),(169,284),(223,213),(223,463),(0,463)],fill="#315866")
sd.polygon([(0,351),(103,257),(223,344),(223,463),(0,463)],fill="#253e4d")
sd.text((112,46),"桌间",font=font(33,True),anchor="mt",fill="#edf6f3")
sd.text((112,95),"TABLETOP",font=font(12),anchor="mt",fill="#aac9d0")
sd.text((112,137),"本局交流",font=font(16,True),anchor="mt",fill="#d0e2e5")
for i,(label,col) in enumerate((("公聊","#e1b866"),("私聊","#80c3ba"),("主持","#bb91bd"),("身份","#809fc5"))):
    x=28+(i%2)*105;y=185+(i//2)*100
    sd.rounded_rectangle((x,y,x+60,y+60),radius=12,fill=col)
    if i<2:
        sd.rounded_rectangle((x+12,y+17,x+48,y+41),radius=4,outline="#244451",width=3)
        sd.polygon([(x+19,y+40),(x+19,y+47),(x+27,y+40)],fill="#244451")
    elif i==2:
        sd.rectangle((x+15,y+17,x+45,y+45),outline="#244451",width=3);sd.line((x+30,y+18,x+30,y+44),fill="#244451",width=2)
    else:
        sd.ellipse((x+16,y+16,x+44,y+44),outline="#244451",width=3)
        sd.line((x+30,y+21,x+30,y+30,x+40,y+34),fill="#244451",width=2)
    sd.text((x+30,y+69),label,font=font(12,True),anchor="mt",fill="#deebe9")
sd.rounded_rectangle((76,444,148,447),radius=2,fill="#dae7e7")
save_texture("phone_screen",screen)
phone_front=Image.new("RGBA",(256,512),(0,0,0,0));pd=ImageDraw.Draw(phone_front)
pd.rounded_rectangle((3,3,252,508),radius=27,fill="#5c747e",outline="#96afb6",width=2)
pd.rounded_rectangle((6,6,249,505),radius=25,fill="#111b22")
phone_front.paste(screen,(16,24))
pd.rounded_rectangle((99,13,156,25),radius=6,fill="#0a151c")
pd.ellipse((116,15,124,23),fill="#385d75",outline="#080c12",width=1)
pd.line((17,28,17,431),fill="#729099",width=1)
pd.line((237,58,237,161),fill="#305462",width=2)
save_texture("phone",phone_front)
phone_rear=Image.new("RGBA",(256,512),(0,0,0,0));pd=ImageDraw.Draw(phone_rear)
pd.rounded_rectangle((3,3,252,508),radius=27,fill="#596b72",outline="#9caeb0",width=2)
pd.rounded_rectangle((8,8,247,503),radius=23,fill="#304750")
for y in range(15,498,2):pd.line((17,y,238,y),fill=(43+y//65,64+y//65,74+y//65),width=1)
pd.rounded_rectangle((23,28,102,134),radius=12,fill="#182d38",outline="#5b747c",width=2)
for y in (53,102):
    pd.ellipse((37,y-17,71,y+17),fill="#546a72",outline="#a9bec5",width=2)
    pd.ellipse((42,y-12,66,y+12),fill="#0a121b",outline="#253545",width=2)
    pd.ellipse((45,y-8,54,y+1),fill="#4a6683")
pd.ellipse((81,50,93,62),fill="#e9e4c6")
pd.text((128,423),"桌间",font=font(18,True),anchor="mt",fill="#9fbbc0")
pd.text((128,451),"TABLETOP",font=font(8),anchor="mt",fill="#6c8e96")
save_texture("phone_back",phone_rear)
for name,col in (("phone_metal","#6f8189"),("phone_rim","#9cabb0"),("phone_glass","#14242e"),("phone_island","#203642"),("phone_port","#0b141c"),("phone_flash","#f4ebca")):
    save_texture(name,Image.new("RGB",(16,16),col))
im=Image.new("RGBA",(64,64),(0,0,0,0));ld=ImageDraw.Draw(im)
ld.ellipse((1,1,62,62),fill="#9eb4bd",outline="#596e7c",width=3)
ld.ellipse((7,7,56,56),fill="#111c29",outline="#344353",width=2)
ld.ellipse((16,16,47,47),fill="#192735")
ld.ellipse((18,13,34,27),fill="#58759b")
ld.ellipse((33,34,43,43),fill="#294351")
save_texture("phone_lens",im)


def write_model(name, payload):
    (MODELS / f"{name}.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def cuboid(start, end, texture="#edge", front=None, back=None):
    faces = {f: {"uv": [0, 0, 16, 16], "texture": texture} for f in ("up", "down", "east", "west", "north", "south")}
    if front:
        faces["south"]["texture"] = front
    if back:
        faces["north"]["texture"] = back
    return {"from": start, "to": end, "faces": faces}


DISPLAY = {
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.92, 0.92, 0.92]},
    "ground": {"rotation": [-90, 0, 0], "translation": [0, 1.5, 0], "scale": [0.65, 0.65, 0.65]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.5, 0.5, 0.5]},
    "firstperson_righthand": {"rotation": [0, -12, 0], "translation": [0, 2, 0], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 12, 0], "translation": [0, 2, 0], "scale": [0.68, 0.68, 0.68]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 14, 0], "scale": [0.5, 0.5, 0.5]},
}
def flip_card_elements(z0, z1, xshift=0, yshift=0):
    result=[]
    for lo,hi,inset in ((1,1.08,.36),(1.08,1.20,.20),(1.20,1.42,.08),
                         (1.42,14.58,0),(14.58,14.80,.08),(14.80,14.92,.20),(14.92,15,.36)):
        e=cuboid([3+inset+xshift,lo+yshift,z0],[13-inset+xshift,hi+yshift,z1],front="#front",back="#back")
        uv=[round(v,6) for v in (inset/10*16,(15-hi)/14*16,16-inset/10*16,(15-lo)/14*16)]
        for side in ("south","north"):
            e["faces"][side]["uv"]=uv
        # Paper bands follow the thin Z dimension on every external edge.
        for side in ("up","down"):
            e["faces"][side]["rotation"]=90
        result.append(e)
    return result


card_display={**DISPLAY,"ground":{"rotation":[-90,0,0],"translation":[0,1.5,0],"scale":[.82,.82,.82]}}
card_elements=flip_card_elements(7.84,8.16)
write_model("_card", {"gui_light": "front", "ambientocclusion":False,"textures": {"front": "tabletop:item/card_back", "edge": "tabletop:item/flip_edge", "back": "tabletop:item/card_back", "particle": "#front"}, "elements":card_elements, "display": card_display})
write_model("_sheet", {"gui_light":"front","textures":{"front":"tabletop:item/script_card","back":"tabletop:item/role_back","edge":"tabletop:item/card_edge","particle":"#front"},"elements":[cuboid([2,0,7.85],[14,16,8.15],front="#front",back="#back")],"display":DISPLAY})
disc=[]
for i in range(28):
    ymin=4+i*8/28;ymax=4+(i+1)*8/28
    dx=math.sqrt(max(0,4**2-((ymin+ymax)/2-8)**2))
    piece=cuboid([8-dx,ymin,7.7],[8+dx,ymax,8.3],front="#front",back="#back")
    uv=[(4-dx)/8*16,(12-ymax)/8*16,(4+dx)/8*16,(12-ymin)/8*16]
    piece["faces"]["south"]["uv"]=uv
    piece["faces"]["north"]["uv"]=[16-uv[2],uv[1],16-uv[0],uv[3]]
    disc.append(piece)
write_model("_token",{"gui_light":"front","textures":{"front":"tabletop:item/identity_card","back":"tabletop:item/identity_card","edge":"tabletop:item/card_edge","particle":"#front"},"elements":disc,"display":DISPLAY})
card_ids = [f"number_{v}" for v in range(13)] + [f"plus_{v}" for v in (2, 4, 6, 8, 10)] + list(SPECIALS) + ["card_back", "identity_card", "script_card", "rules_card"] + [f"role_{r[0]}" for r in ROLES]
for name in card_ids:
    textures = {"front": f"tabletop:item/{name}"}
    if name == "identity_card":
        textures["back"] = "tabletop:item/identity_card"
    elif name.startswith("role_"):
        textures["back"] = "tabletop:item/role_back"
    parent="_token" if name=="identity_card" or name.startswith("role_") else "_sheet" if name in ("script_card","rules_card") else "_card"
    write_model(name, {"parent": f"tabletop:item/{parent}", "textures": textures})

phone_elements=[]
def rounded_layer(x0,y0,x1,y1,z0,z1,material,face=None,north=None):
    radius=.4
    slices=[(y0,y0+.16,.25),(y0+.16,y0+radius,.08),(y0+radius,y1-radius,0),(y1-radius,y1-.16,.08),(y1-.16,y1,.25)]
    for lo,hi,inset in slices:
        e=cuboid([x0+inset,lo,z0],[x1-inset,hi,z1],material,front=face,back=north)
        for direction,ref in (("south",face),("north",north)):
            if ref:
                u0=inset/(x1-x0)*16;u1=16-u0;v0=(y1-hi)/(y1-y0)*16;v1=(y1-lo)/(y1-y0)*16
                e["faces"][direction]["uv"]=[u0,v0,u1,v1]
        phone_elements.append(e)
rounded_layer(4.25,.65,11.75,15.35,7.65,8.35,"#metal")
rounded_layer(4.3,.7,11.7,15.3,8.35,8.42,"#rim")
rounded_layer(4.4,.8,11.6,15.2,8.42,8.48,"#glass")
rounded_layer(4.48,.95,11.52,15.05,8.48,8.51,"#glass",face="#screen")
rounded_layer(4.35,.75,11.65,15.25,7.57,7.65,"#island",north="#back")
phone_elements.append(cuboid([8.85,11.7,7.29],[11.05,14.9,7.59],"#island"))
for center_y in (12.45,14.05):
    for i in range(10):
        lo=center_y-.51+i*1.02/10;hi=lo+1.02/10
        dx=math.sqrt(max(0,.51**2-((lo+hi)/2-center_y)**2))
        e=cuboid([9.87-dx,lo,7.17],[9.87+dx,hi,7.3],"#rim",back="#lens")
        e["faces"]["north"]["uv"]=[max(0,min(16,round(v,6))) for v in ((1-dx/.51)*8,(center_y+.51-hi)/1.02*16,(1+dx/.51)*8,(center_y+.51-lo)/1.02*16)]
        phone_elements.append(e)
phone_elements.append(cuboid([9.0,13.77,7.26],[9.34,14.11,7.3],"#flash"))
for lo,hi in (([11.75,10.2,7.8],[11.88,11.75,8.17]),([4.12,11.3,7.8],[4.25,12.25,8.17]),([4.12,9.95,7.8],[4.25,10.9,8.17])):
    phone_elements.append(cuboid(lo,hi,"#rim"))
phone_elements.append(cuboid([7.32,.59,7.82],[8.68,.67,8.18],"#port"))
phone_elements.append(cuboid([7.6,.575,7.96],[8.4,.6,8.03],"#rim"))
for x in (5.15,5.62,6.09,9.73,10.2,10.67):phone_elements.append(cuboid([x,.605,7.96],[x+.15,.66,8.1],"#port"))
phone_elements.append(cuboid([7.62,14.72,8.5],[8.38,14.91,8.525],"#port"))
phone_display={**DISPLAY,"gui":{"rotation":[8,-18,-7],"translation":[0,0,0],"scale":[.88,.88,.88]},"thirdperson_righthand":{"rotation":[-7,-12,0],"translation":[0,3.6,.5],"scale":[.44,.44,.44]},"thirdperson_lefthand":{"rotation":[-7,12,0],"translation":[0,3.6,.5],"scale":[.44,.44,.44]},"firstperson_righthand":{"rotation":[-4,-60,2],"translation":[1.1,2.5,.3],"scale":[.58,.58,.58]},"firstperson_lefthand":{"rotation":[-4,60,-2],"translation":[1.1,2.5,.3],"scale":[.58,.58,.58]}}
write_model("phone", {"gui_light": "front","ambientocclusion":False,"textures":{"screen":"tabletop:item/phone_screen","front":"tabletop:item/phone","back":"tabletop:item/phone_back","metal":"tabletop:item/phone_metal","rim":"tabletop:item/phone_rim","glass":"tabletop:item/phone_glass","island":"tabletop:item/phone_island","port":"tabletop:item/phone_port","flash":"tabletop:item/phone_flash","lens":"tabletop:item/phone_lens","particle":"#metal"},"elements":phone_elements,"display":phone_display})
openbox=[]
for x0,x1 in ((0,7.75),(8.25,16)):
    floor=cuboid([x0,3,6.5],[x1,13,7],front="#front",back="#back")
    floor["faces"]["south"]["uv"]=[x0,0,x1,16]
    openbox.append(floor)
    for lo,hi in (([x0,3,7],[x0+.35,13,8.2]),([x1-.35,3,7],[x1,13,8.2]),([x0,3,7],[x1,3.35,8.2]),([x0,12.65,7],[x1,13,8.2])):
        openbox.append(cuboid(lo,hi))
        openbox.append(cuboid([lo[0],lo[1],8.18],[hi[0],hi[1],8.30],"#gold"))
    for y in (3.6,12.05):
        openbox.append(cuboid([x0+.18,y,8.30],[x0+.88,y+.48,8.45],"#gold"))
openbox.append(cuboid([7.75,3,6.5],[8.25,13,7.4],"#gold"))
for y in (4.4,7.6,10.8):
    openbox.append(cuboid([7.66,y,6.4],[8.34,y+1,7.55],"#gold"))
for i in range(10):
    a=math.radians(i*36-90);cx=(129+76*math.cos(a))/512*16;cy=13-(157+110*math.sin(a))/320*10
    for j in range(4):
        lo=cy-.58+j*.29;hi=lo+.29;dx=math.sqrt(.58**2-((lo+hi)/2-cy)**2)
        e=cuboid([cx-dx,lo,7.02],[cx+dx,hi,7.19],"#edge",front="#token",back="#token")
        e["faces"]["south"]["uv"]=[max(0,min(16,round(v,6))) for v in ((1-dx/.58)*8,(cy+.58-hi)/1.16*16,(1+dx/.58)*8,(cy+.58-lo)/1.16*16)]
        openbox.append(e)
grimoire_display={**DISPLAY,"gui":{"rotation":[7,-16,0],"translation":[0,0,0],"scale":[.83,.83,.83]},"firstperson_righthand":{"rotation":[0,-24,-3],"translation":[.4,2,0],"scale":[.62,.62,.62]},"firstperson_lefthand":{"rotation":[0,24,3],"translation":[.4,2,0],"scale":[.62,.62,.62]}}
write_model("grimoire", {"gui_light": "front", "textures": {"front": "tabletop:item/grimoire", "back": "tabletop:item/grimoire_back", "edge": "tabletop:item/grimoire_leather","gold":"tabletop:item/clasp","token":"tabletop:item/seat_token", "particle": "#front"}, "elements":openbox,"display":grimoire_display})
deck=[]
for i in range(8):
    xshift=(i%3-1)*.05;yshift=(i%2)*.07;z=6.1+i*.42
    deck.extend(flip_card_elements(z,z+.35,xshift,yshift))
deck.extend(flip_card_elements(9.46,9.78,.05,.08))
write_model("flip_deck", {"gui_light": "front", "textures": {"front": "tabletop:item/card_back", "back": "tabletop:item/card_back", "edge": "tabletop:item/deck_edge", "particle": "#front"}, "elements":deck,"display":{**DISPLAY,"gui":{"rotation":[12,-23,-7],"translation":[0,0,0],"scale":[.86,.86,.86]}}})
write_model("clock_box",{"gui_light":"front","textures":{"front":"tabletop:item/clock_box","back":"tabletop:item/grimoire_back","edge":"tabletop:item/book_leather","particle":"#front"},"elements":[cuboid([3,1,4.5],[13,15,10.8],front="#front",back="#back"),cuboid([2.8,.8,10.8],[13.2,15.2,11.3],front="#front")],"display":DISPLAY})
write_model("vote_token",{"parent":"tabletop:item/_token","textures":{"front":"tabletop:item/vote_token","back":"tabletop:item/vote_token"}})
write_model("death_shroud",{"gui_light":"front","textures":{"front":"tabletop:item/death_shroud","edge":"tabletop:item/shroud_edge","particle":"#edge"},"elements":[cuboid([6,6.5,7.9],[10,9.5,8.1],front="#front",back="#front")],"display":DISPLAY})
branches=[]
for x,y,length,angle in ((6.6,2.1,3.1,-22.5),(7.2,4.7,2.9,22.5),(7.2,7.3,2.9,-22.5),(7.8,9.8,2.8,22.5),(7.9,12.2,2.7,-22.5),(8.6,14.1,1.4,45)):
    e=cuboid([x-.3,y-length/2,7.7],[x+.3,y+length/2,8.3])
    e["rotation"]={"origin":[x,y,8],"axis":"z","angle":angle,"rescale":False};branches.append(e)
# Original carved ghost mask, inferred from the visible Chinese box ornament.
# The retained stem stays in the same grip; the former knot becomes a volume.
im=Image.new("RGB",(128,128),"#382b42");md=ImageDraw.Draw(im)
shadow="#241e30";mid="#89739c";light="#bba9cc"
def bezier(points,steps=16):
    a,b,c,d=points;result=[]
    for i in range(steps+1):
        t=i/steps;u=1-t
        result.append((u**3*a[0]+3*u*u*t*b[0]+3*u*t*t*c[0]+t**3*d[0],u**3*a[1]+3*u*u*t*b[1]+3*u*t*t*c[1]+t**3*d[1]))
    return result
horn_path=bezier(((50,40),(46,22),(36,9),(26,12)))+bezier(((26,12),(12,15),(24,37),(21,45)))[1:]+bezier(((21,45),(20,62),(8,61),(9,49)))[1:]+bezier(((9,49),(8,41),(13,40),(13,46)))[1:]
for mirrored in (False,True):
    path=[(128-x if mirrored else x,y) for x,y in horn_path]
    md.line(path,fill=shadow,width=13,joint="curve");md.line(path,fill=mid,width=9,joint="curve");md.line([(x-1,y-1) for x,y in path],fill=light,width=3,joint="curve")
    for j in range(4,len(path)-3,5):
        x,y=path[j];px,py=path[j-1];nx,ny=path[j+1];length=math.hypot(nx-px,ny-py)
        vx,vy=-(ny-py)/length*4,(nx-px)/length*4;md.line((x-vx,y-vy,x+vx,y+vy),fill=shadow,width=2)
md.polygon([(40,35),(52,26),(61,30),(68,24),(81,31),(91,43),(88,69),(80,82),(64,91),(48,82),(39,67)],fill=shadow)
md.polygon([(43,40),(53,30),(64,35),(70,28),(82,37),(87,48),(82,69),(74,78),(64,83),(53,77),(44,66)],fill=mid)
for x,y in ((47,36),(58,31),(69,31),(78,36),(84,43)):
    md.arc((x-5,y-5,x+6,y+7),10,290,fill=light,width=3)
md.line((43,50,55,48,60,53),fill=shadow,width=4);md.line((68,53,74,48,86,50),fill=shadow,width=4)
md.ellipse((45,54,58,59),fill=light);md.ellipse((70,54,83,59),fill=light)
md.line((63,48,60,64,65,68,70,64),fill=shadow,width=3);md.line((64,49,64,64,67,64),fill=light,width=2)
md.arc((47,66,67,80),180,335,fill=light,width=4);md.arc((62,66,82,80),205,360,fill=light,width=4)
md.arc((53,73,75,83),185,350,fill=shadow,width=3)
for x in (43,49,77,83):md.arc((x-5,65,x+6,87),70,270,fill=light,width=3)
md.ellipse((39,81,89,121),outline=shadow,width=9);md.ellipse((40,81,88,120),outline=mid,width=6);md.arc((41,82,86,117),5,175,fill=light,width=2)
md.ellipse((54,80,75,102),fill=shadow);md.arc((55,80,75,102),15,310,fill=light,width=3);md.arc((60,83,72,97),80,350,fill=mid,width=3)
save_texture("wand_mask",im)
for name,col in (("wand_carving","#796489"),("wand_shadow","#241e30"),("wand_tooth","#baadc9")):
    save_texture(name,Image.new("RGB",(16,16),col))
branches.extend([
    cuboid([5.8,13.0,7.30],[10.2,16.1,8.35],"#carving",front="#mask"),
    cuboid([5.4,13.1,7.6],[6.2,15.15,8.63],"#carving"),
    cuboid([9.8,13.1,7.6],[10.6,15.15,8.63],"#carving"),
    cuboid([6,12.2,7.5],[10,13.05,8.45],"#carving"),
    cuboid([6.8,11.9,7.6],[9.2,12.8,8.4],"#carving"),
    cuboid([7.6,13.5,8.35],[8.4,14.9,8.95],"#carving"),
    cuboid([7.35,13.2,8.65],[8.65,13.6,9.15],"#carving"),
    cuboid([6.15,14.2,8.35],[7.45,14.65,8.66],"#shadow"),
    cuboid([8.55,14.2,8.35],[9.85,14.65,8.66],"#shadow"),
    cuboid([6.44,14.3,8.66],[7.13,14.49,8.72],"#tooth"),
    cuboid([8.87,14.3,8.66],[9.56,14.49,8.72],"#tooth"),
    cuboid([7.0,12.58,8.45],[9.0,12.79,8.7],"#shadow"),
])
branches[6]["faces"]["south"]["uv"]=[4.8,3.7,11.2,10.1]
def carved_tube(path,depth,radius=.20,material="#carving"):
    for i,(x,y) in enumerate(path):
        branches.append(cuboid([x-radius,y-radius,depth-radius],[x+radius,y+radius,depth+radius],material))
for mirror in (False,True):
    # A sampled curve keeps the curling horn readable without excess tiny faces.
    path=[(8+((128-x if mirror else x)-64)*.078,18-(y-8)*.06) for x,y in horn_path[::2]]
    carved_tube(path,7.9,.21)
    for i in range(3,len(path),6):
        x,y=path[i];branches.append(cuboid([x-.20,y-.065,8.105],[x+.20,y+.065,8.16],"#shadow"))
for side in (-1,1):
    curve=[(8+side*(.5+i*.09),13.0+.22*math.sin(i/6*math.pi)) for i in range(13)]
    carved_tube(curve,8.82,.17)
    for row in range(3):
        beard=[(8+side*(1.6-.11*i-row*.28),12.8-.105*i-row*.08) for i in range(10)]
        carved_tube(beard,8.60+row*.035,.16)
# Door knocker ring has a real hole, projected forward of the handle.
ring=[(8+1.25*math.cos(i*2*math.pi/32),10.75+1.16*math.sin(i*2*math.pi/32)) for i in range(32)]
carved_tube(ring,8.86,.20)
swirl=[(8+.44*(1-i/25)*math.cos(i*.40),12.04+.43*(1-i/25)*math.sin(i*.40)) for i in range(22)]
carved_tube(swirl,8.83,.14)
write_model("storyteller_wand",{"gui_light":"front","textures":{"edge":"tabletop:item/storyteller_wand","carving":"tabletop:item/wand_carving","mask":"tabletop:item/wand_mask","shadow":"tabletop:item/wand_shadow","tooth":"tabletop:item/wand_tooth","particle":"#edge"},"elements":branches,"display":{**DISPLAY,"gui":{"rotation":[0,0,-28],"translation":[0,-.8,0],"scale":[.86,.86,.86]}}})

# Two north-facing physical furniture blocks; no third-party texture is used.
rng=random.Random(93417)
wood=Image.new("RGB",(128,128),"#34252c");wd=ImageDraw.Draw(wood)
for y in range(128):
    shade=rng.randint(-5,5);wd.line((0,y,127,y),fill=(57+shade,38+shade,42+shade))
for _ in range(90):
    y=rng.randrange(128);x=rng.randrange(128)
    wd.line((x,y,min(127,x+rng.randrange(10,65)),y),fill=rng.choice(("#4b353b","#261d23","#664749")))
for x,y in ((28,39),(91,91)):
    wd.ellipse((x-11,y-3,x+11,y+3),outline="#261d23",width=1)
wood.save(BLOCK_TEX/"dark_wood.png",optimize=True)
trim=Image.new("RGB",(32,32),"#bb9761");td=ImageDraw.Draw(trim)
for y in range(32):td.line((0,y,31,y),fill=(170+y//2,133+y//2,77+y//2))
trim.save(BLOCK_TEX/"antique_gold.png",optimize=True)
velvet=Image.new("RGB",(64,64),"#532e46");vd=ImageDraw.Draw(velvet)
for y in range(64):
    for x in range(64):
        shade=(x*13+y*7)%7;velvet.putpixel((x,y),(80+shade,39+shade,66+shade))
vd.rectangle((4,4,59,59),outline="#916172",width=1)
for x in range(8,60,5):vd.line((x,4,x+2,4),fill="#c5938f");vd.line((x,59,x+2,59),fill="#c5938f")
velvet.save(BLOCK_TEX/"wine_velvet.png",optimize=True)
felt=Image.new("RGB",(64,64),"#111315")
for y in range(64):
    for x in range(64):
        n=(x*19+y*23)%5;felt.putpixel((x,y),(15+n,17+n,19+n))
felt.save(BLOCK_TEX/"black_felt.png",optimize=True)
Image.open(TEX/"wand_mask.png").save(BLOCK_TEX/"carved_emblem.png",optimize=True)

def material_cube(lo,hi,material):return cuboid(lo,hi,"#"+material)
furniture_display={"gui":{"rotation":[30,225,0],"translation":[0,-1,0],"scale":[.52,.52,.52]},"ground":{"rotation":[0,0,0],"translation":[0,2,0],"scale":[.33,.33,.33]},"fixed":{"rotation":[0,180,0],"translation":[0,0,0],"scale":[.5,.5,.5]},"thirdperson_righthand":{"rotation":[75,45,0],"translation":[0,2.5,0],"scale":[.32,.32,.32]},"firstperson_righthand":{"rotation":[0,45,0],"translation":[0,1,0],"scale":[.38,.38,.38]}}
furniture_textures={"wood":"tabletop:block/dark_wood","gold":"tabletop:block/antique_gold","velvet":"tabletop:block/wine_velvet","felt":"tabletop:block/black_felt","emblem":"tabletop:block/carved_emblem","grimoire":"tabletop:item/grimoire","leather":"tabletop:item/grimoire_leather","token":"tabletop:item/seat_token","particle":"#wood"}
lectern=[
    material_cube([1,0,1],[15,1.35,15],"wood"),
    material_cube([2,1.35,2],[14,1.8,14],"gold"),
    material_cube([4.9,1.8,5.4],[11.1,3,10.6],"wood"),
    material_cube([5.8,3,6],[10.2,14.5,10],"wood"),
    material_cube([5.6,3,5.8],[10.4,3.45,10.2],"gold"),
    material_cube([5.6,12.6,5.8],[10.4,13.05,10.2],"gold"),
    material_cube([3.3,14.2,4],[12.7,15.4,12],"wood"),
]
plaque=material_cube([6.3,7.2,5.88],[9.7,11.8,6],"gold");plaque["faces"]["north"]["texture"]="#emblem";lectern.append(plaque)
tray_rotation={"origin":[8,18.3,8],"axis":"x","angle":-22.5,"rescale":False}
for x0,x1 in ((.2,7.75),(8.25,15.8)):
    shell=material_cube([x0-.10,17.28,1.85],[x1+.10,17.55,14.15],"leather");shell["rotation"]=tray_rotation;lectern.append(shell)
    floor=material_cube([x0,17.55,2],[x1,18.1,14],"wood");floor["faces"]["up"]={"uv":[16-x1,0,16-x0,16],"texture":"#grimoire","rotation":180}
    floor["rotation"]=tray_rotation;lectern.append(floor)
    for lo,hi in (([x0,18.1,2],[x0+.4,19.0,14]),([x1-.4,18.1,2],[x1,19.0,14]),([x0,18.1,2],[x1,19.0,2.4]),([x0,18.1,13.6],[x1,19.0,14])):
        rim=material_cube(lo,hi,"leather");rim["rotation"]=tray_rotation;lectern.append(rim)
        edge=material_cube([lo[0],hi[1]-.16,lo[2]],[hi[0],hi[1]+.06,hi[2]],"gold");edge["rotation"]=tray_rotation;lectern.append(edge)
    for z in (3.0,12.5):
        clasp=material_cube([x0+.3,18.96,z],[x0+.95,19.2,z+.6],"gold");clasp["rotation"]=tray_rotation;lectern.append(clasp)
hinge=material_cube([7.75,17.55,2],[8.25,18.35,14],"gold");hinge["rotation"]=tray_rotation;lectern.append(hinge)
for z in (3,7.5,12):
    hinge_band=material_cube([7.68,17.42,z],[8.32,18.50,z+.7],"gold");hinge_band["rotation"]=tray_rotation;lectern.append(hinge_band)
# Neutral raised markers correspond to the photographed tray, never live roles.
for i in range(10):
    a=math.radians(i*36-90);cx=16-(129+76*math.cos(a))/512*16;cz=2+(157+110*math.sin(a))/320*12
    for j in range(4):
        z0=cz-.43+j*.215;z1=z0+.215;dx=math.sqrt(.43**2-((z0+z1)/2-cz)**2)
        token=material_cube([cx-dx,18.12,z0],[cx+dx,18.28,z1],"gold")
        token["faces"]["up"]={"uv":[max(0,min(16,round(v,6))) for v in ((1-dx/.43)*8,(cz+.43-z1)/.86*16,(1+dx/.43)*8,(cz+.43-z0)/.86*16)],"texture":"#token","rotation":180}
        token["rotation"]=tray_rotation;lectern.append(token)
# Supports beneath the inclined tray connect the book-box to the column.
for x in (4.2,10.8):
    support=material_cube([x,14.2,6.1],[x+1,17.5,9.9],"wood");support["rotation"]=tray_rotation;lectern.append(support)
chair=[]
for x in (2.6,11.8):
    for z in (3.0,11.7):
        chair.append(material_cube([x,0,z],[x+1.6,6.7,z+1.6],"wood"))
        chair.append(material_cube([x-.08,.65,z-.08],[x+1.68,1.0,z+1.68],"gold"))
chair.extend([
    material_cube([2.8,6.2,2.6],[13.2,7.25,13.4],"wood"),
    material_cube([3.15,7.25,2.95],[12.85,8,12.95],"velvet"),
    material_cube([3,6.9,2.48],[13,7.2,2.7],"gold"),
    material_cube([2.6,6.2,12],[4.1,17.3,13.5],"wood"),
    material_cube([11.9,6.2,12],[13.4,17.3,13.5],"wood"),
    material_cube([4.1,10.1,12.45],[11.9,16.6,13.55],"wood"),
    material_cube([4.55,10.55,12.16],[11.45,16.15,12.45],"velvet"),
    material_cube([2.6,16.6,11.9],[13.4,17.6,13.65],"wood"),
    material_cube([3.1,16.5,11.85],[12.9,16.72,12.07],"gold"),
    material_cube([4.2,10.3,12.08],[4.47,16.25,12.3],"gold"),
    material_cube([11.53,10.3,12.08],[11.8,16.25,12.3],"gold"),
    material_cube([3.7,2.8,4.2],[12.3,3.25,4.65],"wood"),
    material_cube([3.7,2.8,11.5],[12.3,3.25,11.95],"wood"),
])
for name,elements in (("grimoire_lectern",lectern),("tabletop_chair",chair)):
    used_textures=furniture_textures if name=="grimoire_lectern" else {k:v for k,v in furniture_textures.items() if k not in ("leather","token")}
    payload={"ambientocclusion":True,"textures":used_textures,"elements":elements,"display":furniture_display}
    (BLOCK_MODELS/f"{name}.json").write_text(json.dumps(payload,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    write_model(name,{"parent":f"tabletop:block/{name}"})
    states={"variants":{f"facing={direction}":{"model":f"tabletop:block/{name}","y":angle} for direction,angle in (("north",0),("east",90),("south",180),("west",270))}}
    (BLOCK_STATES/f"{name}.json").write_text(json.dumps(states,indent=2)+"\n",encoding="utf-8")

# Three bound reading volumes. Flip 7 has a separate identifiable teal rulebook.
for name,title,subtitle,base,trim_color in (("rules_book","规则书","流程 · 投票 · 角色","#2e2639",GOLD),("faq_book","常见问题","FAQ · 疑难与裁定","#eee4c8","#635078"),("flip_rules_book","规则书","翻牌 · 行动 · 计分","#197f88","#f4e5ba")):
    cover,cd=frame(base,trim_color)
    center(cd,"七连翻" if name=="flip_rules_book" else "血染钟楼",17,16,trim_color)
    center(cd,title,47,24,trim_color)
    if name=="rules_book":
        ornament=Image.open(TEX/"wand_mask.png").resize((62,62),Image.Resampling.NEAREST)
        cover.paste(ornament,(33,81))
        cd=ImageDraw.Draw(cover)
    elif name=="faq_book":
        cd.rounded_rectangle((33,84,96,125),radius=7,outline=trim_color,width=3)
        cd.polygon([(45,125),(45,135),(59,125)],fill=trim_color)
        center(cd,"?",82,38,trim_color)
    else:
        for x,y in ((29,93),(47,88),(65,93)):
            cd.rounded_rectangle((x,y,x+34,y+48),radius=3,fill="#197f88",outline=trim_color,width=2)
        center(cd,"7",92,35,trim_color,x=82)
    center(cd,subtitle,153,10,trim_color)
    center(cd,"桌间 · 阅读参考",173,10,trim_color)
    save_texture(name,cover)
    back,bd=frame(base,trim_color)
    center(bd,"桌游参考书",28,17,trim_color)
    for i,line in enumerate(("按章节查阅","随时收起，继续交流","七连翻 · 94 张牌" if name=="flip_rules_book" else "请与说书人确认裁定")):
        center(bd,line,82+i*23,10,trim_color)
    center(bd,"TABLETOP REFERENCE",163,8,trim_color)
    save_texture(name+"_back",back)
    spine=Image.new("RGB",(32,192),"#197f88" if name=="flip_rules_book" else "#403149" if name=="faq_book" else "#2e2639");spd=ImageDraw.Draw(spine)
    for y in (9,15,176,182):spd.line((2,y,29,y),fill=GOLD,width=2)
    for i,char in enumerate("问答" if name=="faq_book" else "规则"):
        spd.text((16,64+i*31),char,font=font(21,True),anchor="mt",fill=GOLD)
    save_texture(name+"_spine",spine)
pages=Image.new("RGB",(32,128),"#eee8d6");pd=ImageDraw.Draw(pages)
for y in range(3,128,7):
    pd.line((0,y,31,y),fill="#c6bda8",width=1)
    pd.line((0,y+1,31,y+1),fill="#faf5e7",width=1)
save_texture("reading_pages",pages)
for name in ("rules_book","faq_book","flip_rules_book"):
    book_elements=[
        cuboid([3.2,1.2,7.02],[12.7,14.8,9.05],"#pages"),
        cuboid([2.8,.9,6.6],[13.1,15.1,7.02],"#edge",front="#back",back="#back"),
        cuboid([2.8,.9,9.05],[13.1,15.1,9.47],"#edge",front="#front"),
        cuboid([2.55,.9,6.6],[3.28,15.1,9.47],"#edge"),
        cuboid([11.8,7.2,9.47],[13.22,8.45,9.7],"#clasp"),
        cuboid([9.9,.35,8.82],[10.5,1.2,9.04],"#ribbon"),
    ]
    book_elements[3]["faces"]["west"]["texture"]="#spine"
    for direction in ("east","west"):book_elements[0]["faces"][direction]["rotation"]=90
    book_display={**DISPLAY,"gui":{"rotation":[8,-22,-6],"translation":[0,0,0],"scale":[.84,.84,.84]},"firstperson_righthand":{"rotation":[0,-20,-4],"translation":[1,3,1],"scale":[.65,.65,.65]},"firstperson_lefthand":{"rotation":[0,20,4],"translation":[1,3,1],"scale":[.65,.65,.65]}}
    write_model(name,{"gui_light":"front","textures":{"front":f"tabletop:item/{name}","back":f"tabletop:item/{name}_back","spine":f"tabletop:item/{name}_spine","pages":"tabletop:item/reading_pages","edge":"tabletop:item/book_leather","clasp":"tabletop:item/clasp","ribbon":"tabletop:block/wine_velvet","particle":"#back"},"elements":book_elements,"display":book_display})

cn = {"itemGroup.tabletop": "围桌 · 桌游", "item.tabletop.card_back": "数字牌背面", "item.tabletop.identity_card": "密封身份卡", "item.tabletop.script_card": "暗流涌动 · 剧本卡", "item.tabletop.rules_card": "血染钟楼 · 规则参考卡", "item.tabletop.grimoire": "说书人的魔典", "item.tabletop.phone": "桌游手机", "item.tabletop.flip_deck": "七连翻牌堆", "item.tabletop.times_two": "倍率牌 ×2", "item.tabletop.freeze": "冻结牌", "item.tabletop.flip_three": "翻三张牌", "item.tabletop.second_chance": "第二次机会牌"}
en = {"itemGroup.tabletop": "Tabletop", "item.tabletop.card_back": "Number Card Back", "item.tabletop.identity_card": "Sealed Identity Card", "item.tabletop.script_card": "Trouble Brewing Script", "item.tabletop.rules_card": "Clocktower Rules Reference", "item.tabletop.grimoire": "Storyteller's Grimoire", "item.tabletop.phone": "Tabletop Phone", "item.tabletop.flip_deck": "Flip Seven Deck", "item.tabletop.times_two": "Multiplier x2", "item.tabletop.freeze": "Freeze", "item.tabletop.flip_three": "Flip Three", "item.tabletop.second_chance": "Second Chance"}
cn.update({"item.tabletop.identity_card":"密封身份圆牌","item.tabletop.clock_box":"血染钟楼 · 游戏盒","item.tabletop.storyteller_wand":"说书人枝条法棒","item.tabletop.vote_token":"投票圆牌","item.tabletop.death_shroud":"死亡裹布标记","item.tabletop.script_card":"暗流涌动 · 角色清单板"})
en.update({"item.tabletop.clock_box":"Clocktower Game Box","item.tabletop.storyteller_wand":"Storyteller Branch Wand","item.tabletop.vote_token":"Voting Token","item.tabletop.death_shroud":"Death Shroud"})
cn.update({"key.tabletop.phone":"拿出桌间手机","category.tabletop":"桌间 · 道具"})
en.update({"key.tabletop.phone":"Open Tabletop Phone","category.tabletop":"Tabletop Props"})
cn.update({"item.tabletop.grimoire_lectern":"展开魔典讲台","block.tabletop.grimoire_lectern":"展开魔典讲台","item.tabletop.tabletop_chair":"桌间软垫椅","block.tabletop.tabletop_chair":"桌间软垫椅"})
en.update({"item.tabletop.grimoire_lectern":"Open Grimoire Lectern","block.tabletop.grimoire_lectern":"Open Grimoire Lectern","item.tabletop.tabletop_chair":"Tabletop Padded Chair","block.tabletop.tabletop_chair":"Tabletop Padded Chair"})
cn.update({"item.tabletop.rules_book":"血染钟楼 · 规则书","item.tabletop.faq_book":"血染钟楼 · 常见问题书"})
en.update({"item.tabletop.rules_book":"Clocktower Rulebook","item.tabletop.faq_book":"Clocktower FAQ Book"})
cn.update({"item.tabletop.flip_rules_book":"七连翻 · 规则书"})
en.update({"item.tabletop.flip_rules_book":"Flip Seven Rulebook"})
for v in range(13):
    cn[f"item.tabletop.number_{v}"] = f"数字牌 {v}"
    en[f"item.tabletop.number_{v}"] = f"Number {v}"
for v in (2, 4, 6, 8, 10):
    cn[f"item.tabletop.plus_{v}"] = f"加分牌 +{v}"
    en[f"item.tabletop.plus_{v}"] = f"Bonus +{v}"
for rid, title, name_cn, _ in ROLES:
    cn[f"item.tabletop.role_{rid}"] = f"角色参考卡 · {name_cn}"
    en[f"item.tabletop.role_{rid}"] = f"Character Reference: {title}"
for locale, payload in (("zh_cn", cn), ("en_us", en)):
    (ASSETS / "lang" / f"{locale}.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


# Static integrity checks; these cannot establish Minecraft render correctness.
models = {p.relative_to(ASSETS/"models").with_suffix("").as_posix(): json.loads(p.read_text(encoding="utf-8")) for p in (ASSETS/"models").rglob("*.json")}
issues = []
for name, payload in models.items():
    parent = payload.get("parent", "")
    if parent.startswith("tabletop:") and parent.split(":",1)[1] not in models:
        issues.append(f"Missing parent: {name}: {parent}")
    def resolve_model(m):
        p = models[m]
        inherited = resolve_model(p["parent"].split(":",1)[1]) if p.get("parent", "").startswith("tabletop:") else {}
        return {**inherited, **p.get("textures", {})}
    textures = resolve_model(name)
    for key, ref in textures.items():
        seen = set()
        while ref.startswith("#"):
            variable = ref[1:]
            if variable in seen or variable not in textures:
                issues.append(f"Unresolved texture: {name}: {key}")
                break
            seen.add(variable)
            ref = textures[variable]
        if ref.startswith("tabletop:") and not (ASSETS / "textures" / (ref.split(":", 1)[1] + ".png")).exists():
            issues.append(f"Missing texture: {name}: {ref}")
    for element in payload.get("elements", []):
        if any(not -16 <= n <= 32 for pt in (element["from"], element["to"]) for n in pt):
            issues.append(f"Element bounds out of range: {name}")
        for face in element["faces"].values():
            if any(not 0 <= n <= 16 for n in face["uv"]):
                issues.append(f"UV out of bounds: {name}")
            if face["texture"].startswith("#") and face["texture"][1:] not in textures:
                issues.append(f"Missing face texture: {name}")
blockstates={p.stem:json.loads(p.read_text(encoding="utf-8")) for p in BLOCK_STATES.glob("*.json")}
for name,state in blockstates.items():
    expected={f"facing={d}" for d in ("north","east","south","west")}
    if set(state.get("variants",{}))!=expected:issues.append(f"Missing horizontal facing: {name}")
    for variant in state.get("variants",{}).values():
        if variant["model"].split(":",1)[1] not in models:issues.append(f"Missing blockstate model: {name}")
registered = card_ids + ["grimoire", "phone", "flip_deck","clock_box","storyteller_wand","vote_token","death_shroud","grimoire_lectern","tabletop_chair","rules_book","faq_book","flip_rules_book"]
for name in registered:
    if f"item.tabletop.{name}" not in cn or f"item.tabletop.{name}" not in en:
        issues.append(f"Missing translation: {name}")
identity = models["item/identity_card"]["textures"]
if identity.get("front") != identity.get("back"):
    issues.append("Identity sides differ")
all_textures=sorted((ASSETS/"textures").rglob("*.png"))
manifest = {"generator": "tools/generate_assets.py", "target": "Minecraft Java 1.21.1 / Fabric", "original_art": True,"resource_revision":"0.3.0", "registered_items": registered, "model_count": len(models),"item_model_count":len(list(MODELS.glob("*.json"))),"block_model_count":len(list(BLOCK_MODELS.glob("*.json"))),"blockstate_count":len(blockstates), "texture_count": len(all_textures),"element_counts":{name:len(models[name].get("elements",[])) for name in ("item/_card","item/flip_deck","item/grimoire","block/grimoire_lectern","item/storyteller_wand","item/flip_rules_book")}, "textures": [{"name": p.relative_to(ASSETS/"textures").as_posix(), "size": list(Image.open(p).size)} for p in all_textures], "static_issues": issues, "in_game_render_verified": False, "blockbench_opened": False}
(ART / "asset-manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
baseline=json.loads(V3_BASELINE.read_text(encoding="utf-8"))
current={p.relative_to(ASSETS).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in ASSETS.rglob("*") if p.is_file()}
asset_baseline=baseline["assets"]
phone_paths=[p for p in asset_baseline if p=="models/item/phone.json" or p.startswith("textures/item/phone")]
chair_paths=[p for p in asset_baseline if p in ("models/item/tabletop_chair.json","models/block/tabletop_chair.json","blockstates/tabletop_chair.json","textures/block/dark_wood.png","textures/block/antique_gold.png","textures/block/wine_velvet.png","textures/block/black_felt.png")]
protected=phone_paths+chair_paths
protected_failures=[p for p in protected if asset_baseline[p]!=current.get(p)]
allowed_changes={"models/item/_card.json","models/item/flip_deck.json","textures/item/card_back.png","textures/item/deck_edge.png"}
allowed_changes.update(f"textures/item/number_{v}.png" for v in range(13))
allowed_changes.update(f"textures/item/plus_{v}.png" for v in (2,4,6,8,10))
allowed_changes.update(f"textures/item/{name}.png" for name in SPECIALS)
changed=[p for p in asset_baseline if asset_baseline[p]!=current.get(p)]
added=[p for p in current if p not in asset_baseline]
unexpected_changes=[p for p in changed if p not in allowed_changes]
unexpected_additions=[p for p in added if p!="textures/item/flip_edge.png"]
for p in protected_failures:issues.append("Protected asset changed: "+p)
for p in unexpected_changes:issues.append("Unexpected asset changed: "+p)
for p in unexpected_additions:issues.append("Unexpected asset added: "+p)
if len(protected)!=19:issues.append("Expected 19 frozen phone/chair files")
if len(registered)!=60 or len(models)!=65 or len(blockstates)!=2 or len(all_textures)!=94:issues.append("Unexpected 0.3 resource inventory")
report={"revision":"0.3.0","protected_phone_paths":phone_paths,"protected_chair_paths":chair_paths,"protected_file_count":len(protected),"protected_failures":protected_failures,"changed_files":changed,"added_files":added,"unexpected_asset_changes":unexpected_changes,"unexpected_asset_additions":unexpected_additions,"registered_item_count":len(registered),"model_count":len(models),"blockstate_count":len(blockstates),"texture_count":len(all_textures),"element_counts":manifest["element_counts"],"static_issues":issues,"previews_are_game_screenshots":False}

def check_old_previews():
    failures=[name for name,sha in baseline["art_png"].items() if not (ART/name).exists() or hashlib.sha256((ART/name).read_bytes()).hexdigest()!=sha]
    report["protected_historical_preview_count"]=len(baseline["art_png"])
    report["unexpected_historical_preview_changes"]=failures
    report["passed"]=not issues and not failures
    (ART/"assets-v3-resource-check.json").write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    if failures:raise RuntimeError("Historical preview changed: "+", ".join(failures))

check_old_previews()
if issues:
    raise RuntimeError("\n".join(issues))

print(json.dumps({"assets_v3_ready":True,"items":len(registered),"models":len(models),"textures":len(all_textures),"qa":"art/assets-v3-resource-check.json"}),flush=True)

def paste_card(board, name, x, y, w=128, shadow=True):
    im = Image.open(TEX / f"{name}.png").convert("RGB")
    h = int(w * im.height / im.width)
    if shadow:
        ImageDraw.Draw(board).rectangle((x + 7, y + 9, x + w + 7, y + h + 9), fill="#101d26")
    board.paste(im.resize((w, h), Image.Resampling.NEAREST), (x, y))


board = Image.new("RGB", (1840, 1440), "#192c35")
d = ImageDraw.Draw(board)
d.text((60, 40), "围桌桌游 / 第一版道具设计", font=font(42, True), fill=PAPER)
d.text((62, 108), "原创像素美术 ｜ 实体卡牌、保密身份、说书人魔典与玩家手机", font=font(21), fill="#a5bebc")
d.text((60, 170), "01   七连翻 · 数字与功能牌", font=font(27, True), fill=GOLD)
for i, name in enumerate(("number_0", "number_7", "number_12", "plus_10", "times_two", "freeze", "flip_three", "second_chance")):
    paste_card(board, name, 60 + i * 216, 220, 180)
d.text((60, 525), "02   暗流涌动 · 四类角色与阅读道具", font=font(27, True), fill=GOLD)
for i, name in enumerate(("role_washerwoman", "role_soldier", "role_drunk", "role_poisoner", "role_imp", "identity_card", "script_card", "rules_card")):
    paste_card(board, name, 60 + i * 216, 575, 180)
d.text((60, 880), "03   说书人与玩家的实体道具", font=font(27, True), fill=GOLD)
paste_card(board, "grimoire", 60, 930, 220)
paste_card(board, "phone", 334, 930, 220)
paste_card(board, "phone_back", 608, 930, 220)
d.text((906, 970), "从道具设计开始保护私密信息", font=font(29, True), fill=PAPER)
for i, line in enumerate(("密封身份卡正反面相同，不显示阵营与身份。", "角色图案用于参考，不对应公开的玩家身份。", "手机外壳不写入玩家消息，实际内容由客户端绘制。", "卡牌正面、背面与侧边使用独立材质。", "魔典具备厚页边、书脊和立体金属扣。")):
    d.text((906, 1028 + i * 40), line, font=font(20), fill="#b5c9c4")
d.text((60, 1360), "道具资源设计板 / 原创美术 / 非游戏内截图", font=font(20), fill="#869e9e")
board.save(ART / "design-board-v3.png", optimize=True)


def perspective_coefficients(dst, src):
    # Solve destination -> source homography for Pillow's PERSPECTIVE transform.
    rows = []
    for (x, y), (u, v) in zip(dst, src):
        rows.append([x, y, 1, 0, 0, 0, -u*x, -u*y, u])
        rows.append([0, 0, 0, x, y, 1, -v*x, -v*y, v])
    for col in range(8):
        pivot = max(range(col, 8), key=lambda k: abs(rows[k][col]))
        rows[col], rows[pivot] = rows[pivot], rows[col]
        divisor = rows[col][col]
        rows[col] = [v / divisor for v in rows[col]]
        for r in range(8):
            if r != col:
                f = rows[r][col]
                rows[r] = [a - f*b for a, b in zip(rows[r], rows[col])]
    return [rows[i][-1] for i in range(8)]


scene = Image.new("RGB", (1600, 1120), "#182933")
d = ImageDraw.Draw(scene)
d.text((56, 42), "一场围桌聚会 · 道具摆放概念", font=font(42, True), fill=PAPER)
d.text((58, 114), "资源纹理摆放示意 / 原创道具美术 / 场地建筑由玩家自行建造", font=font(20), fill="#9fb9b7")
d.polygon([(150, 443), (850, 225), (1450, 509), (744, 924)], fill="#694d38")
d.polygon([(150, 443), (744, 924), (744, 963), (150, 483)], fill="#382b27")
d.polygon([(744, 924), (1450, 509), (1450, 548), (744, 963)], fill="#443226")
d.polygon([(223, 469), (844, 285), (1358, 515), (739, 865)], fill="#2d5554", outline="#b79a67", width=4)


def plane(name, quad, thickness=3, side="#bcb095"):
    im = Image.open(TEX / f"{name}.png").convert("RGBA")
    draw = ImageDraw.Draw(scene)
    round_token=name in ("identity_card","vote_token") or name.startswith("role_")
    shadow = [(x + 11, y + 15) for x, y in quad]
    if not round_token:
        draw.polygon(shadow, fill="#203f40")
        for a, b in ((quad[1], quad[2]), (quad[2], quad[3])):
            draw.polygon([a, b, (b[0], b[1] + thickness), (a[0], a[1] + thickness)], fill=side)
    coeff = perspective_coefficients(quad, [(0, 0), (im.width, 0), (im.width, im.height), (0, im.height)])
    patch = im.transform(scene.size, Image.Transform.PERSPECTIVE, coeff, Image.Resampling.NEAREST)
    if round_token:
        shadow_plane=Image.new("RGBA",scene.size,"#203f40");shadow_plane.putalpha(patch.getchannel("A"))
        scene.paste(shadow_plane,(7,10),shadow_plane)
        side_plane=Image.new("RGBA",scene.size,side);side_plane.putalpha(patch.getchannel("A"))
        scene.paste(side_plane,(0,thickness),side_plane)
    scene.paste(patch, (0, 0), patch)


plane("grimoire", [(777, 346), (952, 422), (794, 531), (620, 447)], 20, "#d6c69d")
for offset in (0, 1, 2, 3):
    plane("card_back", [(435, 467-offset*2), (533, 514-offset*2), (459, 572-offset*2), (361, 522-offset*2)], 3)
plane("number_7", [(539, 581), (642, 629), (554, 700), (450, 644)])
plane("plus_4", [(660, 564), (763, 612), (675, 683), (571, 627)])
plane("number_12", [(779, 547), (882, 595), (794, 666), (690, 610)])
plane("identity_card", [(1045, 493), (1148, 541), (1060, 612), (956, 556)])
plane("script_card", [(972, 655), (1075, 703), (987, 774), (883, 718)])
plane("phone", [(470, 719), (539, 750), (456, 819), (387, 788)], 8, "#1e3540")
plane("phone", [(1179, 568), (1248, 599), (1165, 668), (1096, 637)], 8, "#1e3540")
d = ImageDraw.Draw(scene)
for x, y, label, tx, ty in ((820, 363, "说书人的魔典", 991, 282), (474, 778, "玩家手机", 107, 820), (608, 676, "朝上公开的数字牌", 789, 991), (1048, 549, "密封身份卡", 1200, 806)):
    d.line((x, y, tx, ty), fill="#bda67f", width=2)
    d.ellipse((x-4, y-4, x+4, y+4), fill=GOLD)
    d.text((tx, ty+6), label, font=font(18, True), fill=PAPER)
d.text((56, 1062), "资源概念预览 / 非游戏内截图 / 本 Mod 不提供场地建筑", font=font(19), fill="#98afac")
scene.save(ART / "scene-preview-v3.png", optimize=True)

# Replace the generic card design board with the researched physical components.
def plate(board,name,x,y,w):
    im=Image.open(TEX/f"{name}.png").convert("RGBA")
    h=round(w*im.height/im.width)
    resized=im.resize((w,h),Image.Resampling.NEAREST)
    board.paste(resized,(x,y),resized)


board=Image.new("RGB",(1920,1820),"#e9e3d5");d=ImageDraw.Draw(board)
d.text((54,36),"真实桌游组件 · 第一版道具重设计",font=font(40,True),fill="#372b35")
d.text((56,104),"参考官方实物形制：圆形身份牌、盒式魔典、角色清单板与复古数字牌",font=font(22),fill="#796854")
d.text((56,158),"01   血染钟楼 · 盒装 / 展开魔典 / 纸质清单",font=font(26,True),fill="#763940")
plate(board,"clock_box",58,214,220)
d.text((58,559),"彩印厚盒（闭合形态）",font=font(17,True),fill="#493947")
# The rim diagram illustrates the native raised rails around the two trays.
d.rectangle((328,221,941,617),fill="#463145")
plate(board,"grimoire",340,233,588)
d.text((331,639),"双页黑绒凹槽 · 圆牌座位位 · 夜序参考",font=font(18,True),fill="#493947")
plate(board,"script_card",1010,214,234)
plate(board,"rules_card",1302,214,234)
d.text((1010,579),"完整 22 角色清单板",font=font(17,True),fill="#493947")
d.text((1302,579),"规则参考板",font=font(17,True),fill="#493947")
plate(board,"phone",1648,226,148)
plate(board,"identity_card",1660,481,124)
d.text((1634,649),"手机与统一保密圆牌",font=font(17,True),fill="#493947")
d.text((56,711),"02   暗流涌动 · 22 张圆形角色参考 token",font=font(26,True),fill="#763940")
for i,(rid,role_en,role_cn,cat) in enumerate(ROLES):
    x=58+(i%11)*167;y=767+(i//11)*188
    plate(board,f"role_{rid}",x,y,136)
    d.text((x+68,y+142),role_cn,font=font(15,True),anchor="mt",fill=CATS[cat][0])
d.text((56,1172),"03   七连翻 · 真实牌形与数字布局",font=font(26,True),fill="#4f7270")
for i,name in enumerate(("number_0","number_7","number_12","plus_10","times_two","freeze","flip_three","second_chance","card_back")):
    plate(board,name,58+i*162,1222,132)
plate(board,"vote_token",1544,1230,130)
plate(board,"death_shroud",1730,1230,130)
d.text((1545,1375),"投票圆牌",font=font(16,True),fill="#493947")
d.text((1724,1375),"死亡裹布标记",font=font(16,True),fill="#493947")
d.line([(1556,1465),(1596,1475),(1630,1471),(1663,1494),(1694,1492),(1730,1514),(1765,1507),(1793,1518)],fill="#785439",width=12)
d.line([(1556,1462),(1596,1472),(1630,1468),(1663,1491),(1694,1489),(1730,1511),(1765,1504),(1793,1515)],fill="#ad8359",width=3)
d.ellipse((1768,1488,1801,1520),outline="#4f382c",width=7)
d.line((1769,1509,1778,1487,1795,1499),fill="#785439",width=7)
d.text((1534,1550),"枝条法棒（参考用户实物视频）",font=font(17,True),fill="#493947")
for i,line in enumerate(("几何与配色参考实物；图标、盒面建筑插画与排版重新自绘。", "玩家身份圆牌正反一致，实体魔典内为示意座位，真实状态仅说书人私密阅读。", "资源设计预览，非游戏内截图；未复制官方插画、商标或社区模型。")):
    d.text((58,1640+i*42),line,font=font(21),fill="#776854")
board.save(ART/"design-board-v3.png",optimize=True)

scene=Image.new("RGB",(1600,1120),"#dcd5c7");d=ImageDraw.Draw(scene)
d.text((54,35),"现实桌游组件 · Minecraft 道具摆放概念",font=font(39,True),fill="#42363e")
d.text((56,100),"闭合盒 / 展开魔典 / 圆形身份牌 / 大纸板剧本 / 玩家手机",font=font(21),fill="#796854")
d.polygon([(113,437),(900,227),(1500,524),(705,1000)],fill="#765c44")
d.polygon([(113,437),(705,1000),(705,1025),(113,462)],fill="#463a30")
d.polygon([(705,1000),(1500,524),(1500,549),(705,1025)],fill="#544432")
d.polygon([(180,450),(890,276),(1422,532),(704,938)],fill="#32454a",outline="#a39372",width=4)
plane("grimoire",[(505,360),(925,491),(683,708),(267,544)],18,"#493747")
ImageDraw.Draw(scene).polygon([(1206,308),(1258,283),(1258,542),(1206,567)],fill="#2c293b",outline="#443b49",width=2)
plane("clock_box",[(1041,250),(1206,308),(1206,567),(1041,493)],14,"#493747")
plane("script_card",[(993,637),(1160,702),(1012,820),(846,748)],2)
for i,name in enumerate(("number_7","plus_4","number_12")):
    x=580+i*124;y=733-i*22
    plane(name,[(x,y),(x+97,y+40),(x+20,y+112),(x-76,y+64)],3)
plane("identity_card",[(1240,590),(1347,636),(1262,708),(1158,658)],3)
plane("vote_token",[(1186,737),(1270,773),(1206,826),(1120,788)],3)
plane("phone",[(372,762),(442,792),(360,864),(291,830)],8,"#203941")
# A physical pointer is a project accessory, not a claim about official box contents.
d=ImageDraw.Draw(scene);d.line([(1019,886),(1058,898),(1096,921),(1135,927),(1168,947),(1205,951),(1220,973)],fill="#785439",width=12)
d.line([(1019,883),(1058,895),(1096,918),(1135,924),(1168,944),(1205,948),(1220,970)],fill="#ad8359",width=3)
d.ellipse((1195,947,1230,979),outline="#4f382c",width=7)
for x,y,label,tx,ty in ((686,529,"展开的盒式魔典",374,212),(1150,420,"闭合的彩印游戏盒",1234,191),(1052,729,"大纸板角色清单",1270,899),(1250,651,"统一保密身份圆牌",1325,735),(383,811,"玩家手机",74,887)):
    d.line((x,y,tx,ty),fill="#b69e6a",width=2)
    d.ellipse((x-4,y-4,x+4,y+4),fill=GOLD)
    d.text((tx,ty+7),label,font=font(18,True),fill="#51434b")
d.text((55,1064),"资源概念预览 / 非游戏内截图 / 建筑由玩家自行建造 / 所示魔典摆放不代表真实玩家身份",font=font(19),fill="#776854")
scene.save(ART/"scene-preview-v3.png",optimize=True)

# Project the real generated phone elements, so depth is visible in the review.
def phone_projection(canvas,cx,cy,scale,turn=0,tilt=10,model_path=None):
    def native_model(path):
        child=json.loads(path.read_text(encoding="utf-8"))
        parent=child.get("parent","")
        if parent.startswith("tabletop:"):
            base=native_model(ASSETS/"models"/(parent.split(":",1)[1]+".json"))
            return {**base,**child,"textures":{**base.get("textures",{}),**child.get("textures",{})}}
        return child
    model=native_model(model_path or MODELS/"phone.json")
    ay,ax=math.radians(turn),math.radians(tilt)
    def rotated(p,normal=False):
        x,y,z=p if normal else (p[0]-8,p[1]-8,p[2]-8)
        x,z=x*math.cos(ay)+z*math.sin(ay),-x*math.sin(ay)+z*math.cos(ay)
        y,z=y*math.cos(ax)-z*math.sin(ax),y*math.sin(ax)+z*math.cos(ax)
        return x,y,z
    polygons=[]
    for element in model["elements"]:
        def element_rotate(p,normal=False):
            rotation=element.get("rotation")
            if not rotation:return p
            origin=(0,0,0) if normal else rotation["origin"]
            x,y,z=(p[i]-origin[i] for i in range(3));a=math.radians(rotation["angle"]);co,si=math.cos(a),math.sin(a)
            if rotation["axis"]=="x":y,z=y*co-z*si,y*si+z*co
            elif rotation["axis"]=="y":x,z=x*co+z*si,-x*si+z*co
            else:x,y=x*co-y*si,x*si+y*co
            return (x+origin[0],y+origin[1],z+origin[2])
        x0,y0,z0=element["from"];x1,y1,z1=element["to"]
        faces={
            "south":([(x0,y1,z1),(x1,y1,z1),(x1,y0,z1),(x0,y0,z1)],(0,0,1)),
            "north":([(x1,y1,z0),(x0,y1,z0),(x0,y0,z0),(x1,y0,z0)],(0,0,-1)),
            "east":([(x1,y1,z1),(x1,y1,z0),(x1,y0,z0),(x1,y0,z1)],(1,0,0)),
            "west":([(x0,y1,z0),(x0,y1,z1),(x0,y0,z1),(x0,y0,z0)],(-1,0,0)),
            "up":([(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],(0,1,0)),
            "down":([(x0,y0,z1),(x1,y0,z1),(x1,y0,z0),(x0,y0,z0)],(0,-1,0)),
        }
        for face,(points,normal) in faces.items():
            n=rotated(element_rotate(normal,True),True)
            if n[2]<=.001:continue
            coords=[rotated(element_rotate(p)) for p in points]
            quad=[(cx+x*scale,cy-y*scale) for x,y,z in coords]
            spec=element["faces"][face];key=spec["texture"].lstrip("#")
            path=ASSETS/"textures"/(model["textures"][key].split(":",1)[1]+".png")
            texture=Image.open(path).convert("RGBA");uv=spec.get("uv",[0,0,16,16])
            crop=texture.crop((round(uv[0]/16*texture.width),round(uv[1]/16*texture.height),round(uv[2]/16*texture.width),round(uv[3]/16*texture.height)))
            if crop.width==0 or crop.height==0:continue
            if spec.get("rotation"):crop=crop.rotate(-spec["rotation"],expand=True)
            crop=ImageEnhance.Brightness(crop).enhance(.6+.4*n[2])
            polygons.append((quad,crop,[c[2] for c in coords]))
    pixels=np.asarray(canvas).copy()
    depth_buffer=np.full((canvas.height,canvas.width),-np.inf,dtype=np.float32)
    for quad,texture,depths in polygons:
        xmin=math.floor(min(x for x,y in quad))-1;ymin=math.floor(min(y for x,y in quad))-1
        w=math.ceil(max(x for x,y in quad))-xmin+2;h=math.ceil(max(y for x,y in quad))-ymin+2
        if w<2 or h<2:continue
        if xmin<0 or ymin<0 or xmin+w>canvas.width or ymin+h>canvas.height:
            raise ValueError("Model projection falls outside preview canvas")
        local=[(x-xmin,y-ymin) for x,y in quad]
        coefficients=perspective_coefficients(local,[(0,0),(texture.width,0),(texture.width,texture.height),(0,texture.height)])
        patch=texture.transform((w,h),Image.Transform.PERSPECTIVE,coefficients,Image.Resampling.NEAREST)
        px0,py0=quad[0];dx1=quad[1][0]-px0;dy1=quad[1][1]-py0
        dx2=quad[3][0]-px0;dy2=quad[3][1]-py0
        determinant=dx1*dy2-dx2*dy1
        if abs(determinant)<1e-8:continue
        dz1=depths[1]-depths[0];dz2=depths[3]-depths[0]
        a=(dz1*dy2-dz2*dy1)/determinant
        b=(dx1*dz2-dx2*dz1)/determinant
        face_depth=depths[0]+a*(np.arange(w)+xmin-px0)[None,:]+b*(np.arange(h)+ymin-py0)[:,None]
        patch_pixels=np.asarray(patch)
        region_depth=depth_buffer[ymin:ymin+h,xmin:xmin+w]
        visible=(patch_pixels[:,:,3]>127)&(face_depth>region_depth)
        pixels[ymin:ymin+h,xmin:xmin+w][visible]=patch_pixels[:,:,:3][visible]
        region_depth[visible]=face_depth[visible]
    canvas.paste(Image.fromarray(pixels),(0,0))


details=Image.new("RGB",(1800,1220),"#dfe5e3");dd=ImageDraw.Draw(details)
dd.text((58,35),"一部可拿在手里的手机 · 原生模型结构",font=font(37,True),fill="#253d46")
dd.text((60,101),"金属框 / 多层屏幕玻璃 / 窄边 / 相机岛 / 镜头 / 音量与电源键 / 底部接口",font=font(20),fill="#647b7e")
phone_projection(details,330,660,49,-24,12)
phone_projection(details,920,660,49,154,12)
phone_projection(details,1500,645,39,-19,-25)
for x,y,title in ((135,1085,"正面：玻璃与屏幕分层"),(736,1085,"背面：突出相机模组"),(1328,990,"底端与侧键")):
    dd.text((x,y),title,font=font(23,True),fill="#253d46")
dd.text((60,1161),"来自实际 JSON 几何的软件投影预览，非游戏截图；屏幕是道具主页示意，实时消息由客户端绘制。",font=font(19),fill="#647b7e")
details.save(ART/"phone-details.png",optimize=True)

furniture=Image.new("RGB",(1800,1120),"#e9e3d5");fd=ImageDraw.Draw(furniture)
fd.text((55,32),"围桌实物 · 展开魔典讲台、软垫椅与鬼面法棒",font=font(34,True),fill="#372b35")
fd.text((57,94),"深木 / 酒红紫绒面 / 旧金边 / 立体鬼面 · 由实际原生 JSON 模型投影",font=font(21),fill="#796854")
phone_projection(furniture,410,600,29,158,31,BLOCK_MODELS/"grimoire_lectern.json")
phone_projection(furniture,1040,635,29,154,28,BLOCK_MODELS/"tabletop_chair.json")
phone_projection(furniture,1510,630,30,-18,14,MODELS/"storyteller_wand.json")
fd=ImageDraw.Draw(furniture)
for x,title,caption in ((115,"展开魔典讲台","双凹槽黑绒盒 / 北侧阅读 / 22.5° 倾斜"),(817,"桌间软垫椅","座面 8px / 靠背 17.6px / 朝北"),(1380,"鬼面法棒","保留木柄 / 自绘盒面立体近似")):
    fd.text((x,957),title,font=font(25,True),fill="#493947")
    fd.text((x,1000),caption,font=font(17),fill="#796854")
fd.text((57,1067),"资源模型软件预览，非游戏截图；座椅骑乘与讲台交互由服务端实现，实际动作与光照需在游戏验证。",font=font(19),fill="#796854")
furniture.save(ART/"furniture-details.png",optimize=True)
# Replace the earlier flat branch sketch with the actual updated ghost-mask model.
board=Image.open(ART/"design-board-v3.png").convert("RGB");bd=ImageDraw.Draw(board)
bd.rectangle((1510,1400,1855,1590),fill="#e9e3d5")
phone_projection(board,1675,1495,8,-18,14,MODELS/"storyteller_wand.json")
bd=ImageDraw.Draw(board);bd.text((1525,1580),"鬼面法棒（盒面自绘近似）",font=font(17,True),fill="#493947")
expanded=Image.new("RGB",(1920,3060),"#e9e3d5");expanded.paste(board,(0,0));expanded.paste(furniture,(60,1860))
expanded.save(ART/"design-board-v3.png",optimize=True)

books_preview=Image.new("RGB",(1800,1150),"#e9e3d5");bp=ImageDraw.Draw(books_preview)
bp.text((55,35),"手持阅读 · 钟楼规则、常见问题与七连翻规则",font=font(37,True),fill="#372b35")
bp.text((57,106),"厚封皮 / 独立书脊 / 米白页边 / 金扣 / 绒面书签 · 原生模型软件预览",font=font(21),fill="#796854")
phone_projection(books_preview,310,565,39,-26,9,MODELS/"rules_book.json")
phone_projection(books_preview,900,565,39,-26,9,MODELS/"faq_book.json")
phone_projection(books_preview,1490,565,39,-26,9,MODELS/"flip_rules_book.json")
bp=ImageDraw.Draw(books_preview)
for x,label in ((122,"血染钟楼 · 规则书"),(708,"血染钟楼 · 常见问题书"),(1312,"七连翻 · 规则书")):
    bp.text((x,965),label,font=font(25,True),fill="#493947")
bp.text((57,1040),"三本书：对空气或方块右键阅读；剧本清单板：普通右键阅读、潜行右键平放。",font=font(22),fill="#796854")
bp.text((57,1090),"资源软件预览，非游戏截图；正文、章节与搜索由客户端阅读界面实现。",font=font(19),fill="#796854")
books_preview.save(ART/"books-details.png",optimize=True)
expanded_books=Image.new("RGB",(1920,4270),"#e9e3d5");expanded_books.paste(expanded,(0,0));expanded_books.paste(books_preview,(60,3100))
expanded_books.save(ART/"design-board-v3.png",optimize=True)

flip_details=Image.new("RGB",(1920,2880),"#e9e3d5");fp=ImageDraw.Draw(flip_details)
fp.text((55,32),"七连翻 · 0.3 实体牌面与圆角纸牌",font=font(38,True),fill="#243d3e")
fp.text((57,105),"512×768 自绘牌面 / 双向角标 / 6与9下划线 / 短规则提示 / 高分辨率双向卡背",font=font(24),fill="#6b7770")
for value in range(13):
    row,col=divmod(value,7);x,y=57+col*266,176+row*430
    plate(flip_details,f"number_{value}",x,y,232)
    fp.text((x,y+362),f"数字 {value}",font=font(24,True),fill="#405554")
for i,name in enumerate(("plus_2","plus_4","plus_6","plus_8","plus_10","card_back")):
    plate(flip_details,name,57+i*266,1036,232)
    fp.text((57+i*266,1398),cn["item.tabletop."+name],font=font(24,True),fill="#405554")
for i,name in enumerate(SPECIALS):
    plate(flip_details,name,57+i*466,1490,376)
    fp.text((57+i*466,2070),cn["item.tabletop."+name],font=font(26,True),fill="#405554")
fp.text((57,2170),"下方直接读取本轮原生模型；双向角标的另一端旋转180°，不把正文方向错误镜像。",font=font(23),fill="#6b7770")
phone_projection(flip_details,360,2440,30,-27,14,MODELS/"flip_deck.json")
phone_projection(flip_details,950,2440,29,-27,14,MODELS/"number_7.json")
phone_projection(flip_details,1520,2440,30,-26,9,MODELS/"flip_rules_book.json")
fp=ImageDraw.Draw(flip_details)
for x,label in ((188,"真实分层的厚牌堆"),(792,"薄纸牌与圆角模型"),(1322,"独立七连翻规则书")):
    fp.text((x,2690),label,font=font(26,True),fill="#405554")
fp.text((57,2770),"资源软件预览，非游戏截图；新纸边不修改公共圆牌、书本或手机纹理。",font=font(24),fill="#6b7770")
fp.text((57,2820),"七连翻94张牌组及正式游戏规则保持服务端权威；详细规则继续通过实体规则书阅读。",font=font(22),fill="#6b7770")
flip_details.save(ART/"flip-details-v3.png",optimize=True)

check_old_previews()

(ROOT / "docs/assets.md").write_text("""# 第一版道具与资源\n\n运行目标：Minecraft Java 1.21.1、Fabric，资源随 Mod 在客户端加载。客户端、服务端安装要求以主 README 为准。\n\n源：`tools/generate_assets.py`。用 Python + Pillow 执行生成；纹理与 Java 原生 JSON 模型位于 `src/main/resources/assets/tabletop/`。生成器仅写资源、预览和本说明，不修改游戏逻辑。\n\n## 实体道具\n\n- 数字牌 0–12、加分牌 +2/+4/+6/+8/+10、倍率、冻结、翻三张、第二次机会、牌背和厚牌堆。\n- 暗流涌动 22 张角色参考牌，镇民蓝、外来者蓝灰、爪牙酒红、恶魔深红。\n- 玩家密封身份卡、剧本卡、规则参考卡、带厚页边/书脊/金属扣的魔典、带背壳/侧键的手机。\n\n卡牌本体是 X=3..13、Y=1..15、Z=7.75..8.25 的薄长方体；南面（+Z）为正面，北面为背面。UV 均在 0..16 的原生归一化坐标。纹理正面通常为 128×192，侧边为 16×16；按最近邻采样保持像素边缘。`ground` 变换绕 X 旋转 90° 并缩放，可用于平放桌面；桌面渲染器应使用该变换或自行进行等效旋转。资源 GUI 与手持变换已设置，但最终方向/光照须在游戏中核验。\n\n`identity_card` 的正反面完全相同，无任何角色/阵营颜色或图标。角色身份、醉酒、中毒与消息内容只能由已校验权限的客户端界面或私密网络消息显示；不应把实际身份牌模型直接交给玩家或放到公开桌面。`role_*` 是参考图案，不是玩家身份编码。\n\n手机 PNG 是空消息示意外壳，不存储玩家文字或身份，实时文字由客户端绘制。规则与角色能力详情在客户端阅读界面显示，避免把长篇规则塞进纹理。\n\n## 美术与来源\n\n所有纹理、门灯图案、角色小图标、书皮与手机外壳均为本工程原创程序像素美术，未复制官方插画、角色图标、标志或第三方模型。生成器与 PNG/JSON 是可编辑源；本轮未打开 Blockbench，也不提供虚构的 `.bbmodel` 母版。\n\n角色名称、分类和规则主题以官方暗流涌动资料为依据；这不代表官方授权或素材许可。规则内容和可分发范围由项目主说明单独记录。官方资料：\n\n- https://wiki.bloodontheclocktower.com/Trouble_Brewing\n- https://bloodontheclocktower.com/\n- https://theop.games/products/flip-7\n\n## 验证\n\n生成器检查 JSON、父模型、纹理引用、UV 边界、物品翻译及身份背面一致性。`art/asset-manifest.json` 保存检查结果。`art/design-board.png` 为道具设计板；`art/scene-preview.png` 是程序绘制的摆放概念预览，明确标注非游戏截图，不包含需要用户建造的场地交付。\n\n已进行静态检查与 PNG 查看；尚未完成游戏内渲染、掉落物/手持姿态、双面方向、资源重载及多人隐私实机验证。\n""", encoding="utf-8")
real_doc = """# 第一版实物桌游道具\n\n目标：Minecraft Java 1.21.1 / Fabric / Java 21。资源随 Mod 在客户端加载，安装侧以主 README 为准。源文件为 `tools/generate_assets.py`，使用 Python + Pillow 生成 `src/main/resources/assets/tabletop/`、资源预览和本说明。\n\n## 按实物形制重设计\n\n血染钟楼的身份组件是圆形角色 token，魔典是游戏盒本身展开后的双托盘黑绒面，不是一本普通厚页书。本版已将 22 个 `role_*` 改为薄圆牌，`identity_card` 改为统一中性背面的密封圆牌；`grimoire` 为双页凹槽盒式展开模型，`clock_box` 为闭合彩印厚盒；`script_card` 与 `rules_card` 是较大的角色清单纸板。\n\n另有圆形 `vote_token`、`death_shroud` 死亡裹布标记。死亡裹布轮廓作项目自绘近似，未宣称逐毫米复刻。`storyteller_wand` 是项目自定义主持指示棒，官方盒装清单未列出法棒，不称其为官方组件。手机是本项目社交道具，仍保留屏幕、背壳、厚度和侧键。\n\n七连翻采用接近实物的扑克牌比例、米白牌面、显眼大数字、复古扇形放射装饰与按数字变化的色边。加分、倍率及行动牌有各自醒目的图形。所有图案与中文排版自行绘制，未下载官方插画或商标用于打包。\n\n## 原生 Java 模型约定\n\n模型用 Minecraft 1.21.1 原生 `elements` JSON，不依赖外部几何库。卡牌 X=3..13 / Y=1..15 / Z=7.75..8.25；圆牌在 X/Y=4..12，以 28 个横向窄条近似圆形，Z=7.7..8.3；清单板 X=2..14 / Y=0..16。南面 +Z 为可阅读面，北面为背面。`ground` 绕 X 旋转 90° 平放，可用于桌面 ItemDisplay。\n\n展开魔典为 X=0..16 / Y=3..13 的左右凹槽，带书脊连接与凸起边框，外装是厚盒皮面。其静态纹理是中性示意座位，不记录真实玩家身份或醉毒状态；真实魔典数据只能由经服务端校验的说书人私密界面展示。\n\n`identity_card` 正反纹理完全相同，无阵营和角色图标。`role_*` 是公开参考道具，不作为发放给玩家的真实身份凭证。手机 PNG 只是空消息外壳，实时消息与私密信息由客户端按权限显示。\n\n## 参考来源\n\n检索日期：2026-09-30。官方页面用于确认组件与形制，未复制照片作为游戏贴图。\n\n- [官方盒装清单](https://bloodontheclocktower.com/pages/whats-in-the-box)：角色圆牌、角色清单、盒体构成魔典、支架、布袋及其他标记。\n- [官方购买页与实物照片](https://bloodontheclocktower.com/pages/buy)：99 角色 token、120 提醒 token、投票/生死组件、夜序表、魔典夹。\n- [官方组件照片](https://cdn.shopify.com/oxygen-v2/24098/8185/16590/116442/build/_assets/buy-page-box-content-HEIT7U5M.jpg)：游戏盒、纸板、圆 token 与收纳整体形态。\n- [官方角色与配套组件实物照片页](https://checkout.bloodontheclocktower.com/products/blood-on-the-clocktower-the-game)。\n- [The Op 七连翻](https://theop.games/products/flip-7)：94 张牌、实物牌组与盒装。\n- [官方暗流涌动](https://wiki.bloodontheclocktower.com/Trouble_Brewing)：角色名与分类。\n\n本工程图标、盒面建筑、像素美术和模型均为原创自绘；游戏规则、角色名与玩法来自对应桌游，不冒称原创玩法或官方授权。未打开 Blockbench，未虚构 `.bbmodel` 工程。\n\n## 验证与预览\n\n`art/asset-manifest.json` 记录物品清单、PNG 尺寸和静态模型检查。检查包括父级、纹理引用、UV 范围、元素范围、翻译与身份背面一致性。\n\n`art/design-board.png` 展示全部 22 圆形角色 token 与实物组件重设计；`art/scene-preview.png` 是资源摆放概念，明确标注非游戏内截图。已查看图片并修正排版；尚未完成实际游戏中的方向、光照、掉落物、手持、资源重载和多人隐私渲染验证。\n"""
real_doc=real_doc.replace("`storyteller_wand` 是项目自定义主持指示棒，官方盒装清单未列出法棒，不称其为官方组件。","`storyteller_wand` 参考用户提供的中文盒装视频中的木雕枝条法棒，保留弯曲枝条与末端卷结；尚未核实法棒是否属于官方常规盒装组件。")
real_doc=real_doc.replace("图标、盒面建筑、像素美术和模型均为原创自绘","图标、盒面紫色装饰怪面、像素美术和模型均为原创自绘")
real_doc=real_doc.replace("所有图案与中文排版自行绘制","数字牌底部保留英文数字词，配合用户实物照片调整左右扇形装饰和青绿牌背。所有图案与排版自行绘制")
real_doc += "\n## 用户实物参考\n\n已查看 `C:/Users/wxj/Downloads/Screenshot_20260930_111406.jpg`（中文大盒与木雕枝条法棒）、`Screenshot_20260930_112030.jpg`、`Screenshot_20260930_112206.jpg`（七连翻牌面、青绿色盒体/牌背）。这些用户参考是本轮外形主要依据；保持深紫盒面、金绿竖边、白色中文标题，中央怪面重新自绘，未截取原图直接贴图。\n"
real_doc=real_doc.replace("Python + Pillow", "Python + Pillow + NumPy")
real_doc=real_doc.replace("绕 X 旋转 90°", "绕 X 旋转 -90°")
real_doc=real_doc.replace("死亡裹布轮廓作项目自绘近似，未宣称逐毫米复刻。", "死亡裹布按用户照片改为小块纯黑布片，带简化折叠边，不印骷髅或文字；尺寸与折痕为近似。")
real_doc=real_doc.replace("手机是本项目社交道具，仍保留屏幕、背壳、厚度和侧键。", "手机是本项目社交道具：独立金属机框、黑色窄边、多层玻璃屏、磨砂背壳、凸起双摄模组与闪光灯、音量/电源侧键、底端 USB-C 接口和扬声器孔。正面是原创青绿壁纸与公聊/私聊/主持/身份四个入口，不在纹理中存放聊天内容。")
real_doc += "\n## 本轮手机与中文盒面\n\n进一步查看用户的 `Screenshot_20260930_114109.jpg`、`114047`、`114038`、`114122`、`114032`、`113646`、`113844`、`113705`、`113831`、`113735`、`113657` 同名前缀 JPG。其中 114047 核对双栏清单板与红色分类条，114122 核对黑色布片，114032 核对盒面。盒面主要文字现为大字“染”和“钟楼谜团”，项目内玩法名称仍为血染钟楼；怪面与边饰重新绘制，不复制照片插画。\n\n手机参考 [MCPhone 官方项目](https://www.curseforge.com/minecraft/mc-mods/mcphone) 与 [公开源码](https://github.com/november521/mcphone)（MIT）中的分层机框、摄像头和接口形制。本工程未引入其依赖、功能代码或纹理，模型坐标、屏幕和外壳均重新设计。\n\n`art/phone-details.png` 是直接读取本工程原生手机 JSON 的软件正反面投影，采用深度缓冲验证组件遮挡，明确标注非游戏截图。模型正面为 +Z，`ground` 的 -90° X 旋转将可读面朝上；桌面 ItemDisplay 不应额外重复旋转。实际手持角度、光照和客户端实时画面仍由游戏内验证确认。\n"
real_doc=real_doc.replace("保留弯曲枝条与末端卷结", "保留木柄握持，头部依据盒面鬼脸自绘成立体角、眉眼、鼻与嘴的近似雕刻")
real_doc += "\n## 实体讲台、软垫椅与法棒头部\n\n新增 `grimoire_lectern` 与 `tabletop_chair` 原生方块、同名物品模型和四向 blockstate。默认 `facing=north`：讲台读者站北侧，黑绒双托盘向南升高；托盘绕 X=-22.5°，上面纹理旋转 180° 从北侧阅读，左侧为中性座位圆牌、右侧为夜序参考。下部有深木底座与立柱，托盘为酒红紫边、旧金细线和金扣。模型最高旋转后约 21.4px，原始坐标全部在 0..24 范围。讲台支撑与托盘使用同一旋转，避免支撑穿出阅读表面。\n\n椅子默认开放座位朝北、靠背在南边（+Z），座面最高 8px，靠背最高 17.6px。深木腿架、金色脚套、酒红紫软垫与低靠背为项目自绘近似；真实坐姿、骑乘偏移与交互由游戏逻辑验证，资源本身不宣称已实现可坐。四向均为 north=0 / east=90 / south=180 / west=270 的 Y 旋转，命名与 Minecraft `HorizontalFacingBlock` 对齐。\n\n法棒保留原柄与握持变换，头部自绘成立体鬼面、向外斜角、眉眼、鼻、齿与下颚，不再使用小木结。依据仅为用户盒面可清晰见到的图形，未截取照片、未宣称精确官方模型。新增纹理为自绘木纹、旧金、绒面、黑绒和鬼面；用户照片只用于外形核对，不在 assets 或打包资源中。\n\n`art/furniture-details.png` 直接读取讲台、椅子与法棒原生 JSON，含 native 元素旋转与深度缓冲；它是软件资源预览，非游戏截图。`art/design-board.png` 增加同一组模型预览。manifest 递归计数全部 item/block 模型，并单列 blockstate 数，检查四个 facing 的模型引用。\n"
real_doc += "\n## 规则书、FAQ 书与剧本卡阅读\n\n新增 `rules_book`（血染钟楼规则书）与 `faq_book`（常见问题书），均是原生厚页书模型：独立前后封皮、书脊、米白多层页边、金扣与书签。规则书为深紫金边，FAQ 书为米白紫边，封面中文标题明显区分。封面鬼面引用本工程自绘纹理，未截取官方图片。注册为堆叠上限 1 的阅读道具，不消耗书本、不放置临时展示实体。\n\n两本书对空气或方块右键都请求阅读；专用 UseBlockCallback 也使书本在箱子等可交互方块前优先打开阅读，允许主副手。现有 `script_card` 继续保留清单板：普通右键读取详细剧本，潜行右键方块才平放桌面。旧 `rules_card` 保持兼容。正文、角色说明、FAQ 与分页由对应客户端阅读实现，本资源不把全部规则写入纹理。\n\n`art/books-details.png` 直接从原生 JSON 投影两书封面、页边与书脊，明确标注非游戏截图。规则书与 FAQ 新增 2 个模型、7 张纹理；生成前后的冻结资源 SHA 对比另记录在 `art/books-resource-check.json`，语言字典因新增书名允许变化。手机、讲台、椅子、法棒与其他既有模型/贴图保持字节一致。\n"
real_doc=real_doc.replace("卡牌 X=3..13 / Y=1..15 / Z=7.75..8.25","卡牌 X=3..13 / Y=1..15 / Z=7.84..8.16，使用 5 条横向薄纸切片近似圆角")
real_doc=real_doc.replace("新增 `rules_book`（血染钟楼规则书）与 `faq_book`（常见问题书）","阅读道具包含 `rules_book`（血染钟楼规则书）、`faq_book`（常见问题书）与 `flip_rules_book`（七连翻规则书）")
real_doc=real_doc.replace("两本书对空气或方块右键都请求阅读","三本书对空气或方块右键都请求阅读")
real_doc=real_doc.replace("`art/books-details.png` 直接从原生 JSON 投影两书封面、页边与书脊，明确标注非游戏截图。规则书与 FAQ 新增 2 个模型、7 张纹理；生成前后的冻结资源 SHA 对比另记录在 `art/books-resource-check.json`，语言字典因新增书名允许变化。手机、讲台、椅子、法棒与其他既有模型/贴图保持字节一致。","`art/books-details.png` 直接从原生 JSON 投影三书封面、页边与书脊，明确标注非游戏截图。0.1.0 的两本钟楼书记录保存在 `art/books-resource-check.json`；本轮模型变化与冻结资源哈希比较保存在 `art/assets-v2-resource-check.json`，不将历史冻结检查误称为本轮所有资源不变。")
real_doc += f"""\n## 0.2.0 牌组与魔典模型完善\n\n七连翻数字与行动牌升级为 256×384 自绘 PNG。数字 0–12 以米白纸底、醒目大数字、左上/右下角标与各自颜色区分，边饰留在数字之外。加分牌为黄色面板；倍率、冻结、翻三张、第二次机会分别为 ×2、雪花、三张叠牌与循环一次机会图形，中文短标题与主题色直接帮助桌面辨认。没有把全套规则挤进纹理，也没有改变 94 张牌组或牌数规则。卡牌为 5 条圆角切片，牌堆为 9 个错开约 0.05px 的原生实体层，纸边纹理可见细线。\n\n独立 `flip_rules_book` 是青绿封面、米金标题、叠牌 7 图形的厚页规则书，拥有前后封皮与书脊。堆叠上限 1；空气/方块右键请求公共资料，不需要参与对局。其章节与规则正文由 `ReferenceLibrary` 提供，本资源不伪造游戏规则。\n\n魔典为金边紫色皮革双托盘，增加书壳底部、铰链带、盒角金扣与中性实体座位标记。手持魔典 GUI 带轻微转角以展示厚壳；讲台保留原倾角、基座、立柱位置和碰撞逻辑，增加外壳和浅标记。标记纹理始终是通用时钟图形，不能显示玩家真实身份、醉毒或行动信息。法杖从方块脸调整为更窄的鬼面，眉眼、卷角、胡须和前凸空心门环仍为本工程自绘近似；曲线采样减少细碎元素，由原 320 个降为 {len(branches)} 个元素。没有使用用户 Meshy 账号。\n\n本轮生成 {len(registered)} 个注册物品资源、{len(models)} 个 item/block 模型、{len(blockstates)} 个 blockstate、{len(all_textures)} 张 PNG；静态检查问题为 {len(issues)}。手机所有模型/纹理和软垫椅模型/纹理通过基线哈希比较，9 座 NBT 与座位逻辑不在本轮资源修改范围。`art/flip-details.png` 展示实际 PNG 与原生 JSON 牌堆/纸牌/规则书投影；`art/furniture-details.png`、`art/books-details.png` 同步更新。这些均为软件预览，最新资源的游戏内验证由根任务另行记录。\n"""
real_doc=real_doc.replace("# 第一版实物桌游道具", "# 0.3.0 实物桌游道具（资源修订）",1)
real_doc=real_doc.replace("使用 5 条横向薄纸切片近似圆角", "使用 7 条横向薄纸切片近似圆角")
real_doc=real_doc.replace(f"本轮生成 {len(registered)} 个注册物品资源、{len(models)} 个 item/block 模型、{len(blockstates)} 个 blockstate、{len(all_textures)} 张 PNG；静态检查问题为 {len(issues)}。", "0.2.0 历史资源包含 60 个注册物品、65 个模型、2 个 blockstate、93 张 PNG，静态检查问题为 0。")
real_doc += "\n## 0.3.0 七连翻牌面与纸边\n\n数字 0–12、5种加分牌、4种行动/倍率牌与青绿牌背统一使用 512×768 自绘 PNG。角标成对旋转 180°，6 与 9 的主数字及角标均带下划线；保留米白纸面、大数字、英文数字词、色边和扇形装饰。卡背有双向标题、大字 7 与细金边。新增参考 `Screenshot_20260930_135738.jpg` 和 `Screenshot_20260930_140045.jpg` 仅用于阅读层次和纸面形式，不截取照片作为贴图。\n\n短规则经规则模块核对：倍率仅翻倍数字合计；冻结只使指定仍在本轮的玩家收手；翻3提示爆牌/七种数字即停及行动牌生效；机会提示抵消一次重复并弃置重复与保护牌，多余保护只能转赠仍在本轮且没有保护的其他玩家，无人可用则弃置。完整规则通过实体书阅读，资源不修改正式规则代码。\n\n`_card` 保持 X=3..13、Y=1..15、Z=7.84..8.16，圆角增加为7条切片，UV按切片截取且仍在0..16；纸边新增独立 `flip_edge.png`，不修改公共 `card_edge.png`。仅七连翻单牌 ground 比例从0.65改为0.82；其他物品变换不变。9层圆角牌堆共63元素，增加纸层纹理但不增加展示实体。\n\n本轮为60注册物品、65模型、2个blockstate、94张PNG。`art/assets-v3-baseline.json` 与 `art/assets-v3-resource-check.json` 核对全部非七连翻资源、19个手机/椅子文件和历史预览的SHA-256；V2报告和PNG保留原文件。新增 `art/flip-details-v3.png`、`art/design-board-v3.png`、`art/scene-preview-v3.png` 均为软件预览，非游戏截图。游戏内验证由根任务另行记录。\n"
(ROOT/"docs/assets.md").write_text(real_doc,encoding="utf-8")
print(json.dumps({"items": len(registered), "models": len(models), "textures": manifest["texture_count"], "issues": issues, "preview": str(ART / "flip-details-v3.png")}, ensure_ascii=False))
