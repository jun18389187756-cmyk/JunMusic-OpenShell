#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
JunMusic 内置字体裁剪压缩脚本（可反复重跑，幂等）
=================================================

为什么要这个脚本
----------------
线上包 7,368,574 B 里，res/font 两款字体在 APK 内 deflate 后占 **2,813,075 B（约 38%）**
（实测 JunMusic-6.0.0-61-arm64.apk：res/7N.ttf=1,776,186、res/Z2.ttf=1,036,889）。
现在要再加两款新字体，不做处理包体会直接冲破 10MB。
Android 的 res/font 只认 ttf/otf —— **不能**输出 woff2（系统字体加载器不认），
所以能压的只有三件事：**少放字、少放表、少放点**。

流程（四步，每步的"为什么"写在对应小节）
----------------------------------------
  1. 备份原件   -> tools/font_originals/（只收一次，绝不覆盖已有备份）
  2. 定保留字符集 -> App 源码真实用到的字符 ∪ GB2312 一级字 3755（含 3500 常用字）
                    ∪ 标点/符号/姓名用字
  3. pyftsubset  -> 按字符集裁字形 + 砍表 + 砍 name 条目 + 砍 OpenType 特性
  4. glyf 规约   -> 坐标量化 + 冗余轮廓点删除（本次压缩的主力增量，pyftsubset 做不到）

回退办法（重要）
----------------
四款字体的未裁剪原件永久躺在 tools/font_originals/：
    orig_font_childfun.ttf        1,552,384 B   线上原版
    orig_font_chillround.ttf      3,314,220 B   线上原版
    orig_zhiyongdatong.ttf        5,196,288 B   用户上传原始（志勇大同体）
    orig_lemibobo.ttf             2,599,108 B   用户上传原始（乐米波波体）
想回到未压缩状态（一条命令）：
    python3 tools/subset_fonts.py --restore
想换档位（更狠 / 更保守），改跑：
    python3 tools/subset_fonts.py --tier fine       # 保守：grid6/tol6
    python3 tools/subset_fonts.py --tier standard   # 中间档：grid10/tol10
    python3 tools/subset_fonts.py --tier extreme    # 默认（最狠）：grid14/tol18
    python3 tools/subset_fonts.py --hanzi corpus    # 只留仓库里真出现过的汉字（体积最小档）
    python3 tools/subset_fonts.py --dry-run         # 只测量，不写回 res/font
    python3 tools/subset_fonts.py --report-only     # 只看现状尺寸
重跑绝对安全：脚本**只从 font_originals 的原件读入**，不会把已裁剪的结果二次裁剪
（二次裁剪有害：字符集已被缩小会误判覆盖，量化误差还会叠加）。

依赖：fontTools(>=4.x)；--montage 目检功能需要 Pillow+numpy（缺失自动跳过）。
"""

from __future__ import annotations

import argparse
import math
import os
import re
import shutil
import sys
import tempfile
import zipfile
import zlib

try:
    from fontTools.subset import Options, Subsetter
    from fontTools.ttLib import TTFont
    from fontTools.ttLib.tables._g_l_y_f import flagOnCurve as ON_CURVE, GlyphCoordinates
except ImportError:
    sys.exit("缺 fontTools：pip install fonttools")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "font")
ORIG_DIR = os.path.join(ROOT, "tools", "font_originals")
SRC_DIR = os.path.join(ROOT, "app", "src", "main")
UPLOAD_FONTS = "/tmp/fonts"     # 用户上传字体解压位置（仅首轮取用，之后一律读 font_originals）

# ========================= 字体清单 =========================
# orig_name 用 ASCII：备份目录里放中文名给 git/CI 找麻烦，且能防"跨平台打包"踩坑。
# dst 必须是小写字母/数字/下划线 + .ttf，否则 aapt 直接报错（资源名规则）。
FONTS = [
    dict(key="childfun",      dst="font_childfun.ttf",      orig_name="orig_font_childfun.ttf",
         first_source=[os.path.join(FONT_DIR, "font_childfun.ttf")], label="ChildFun 童趣体"),
    dict(key="chillround",    dst="font_chillround.ttf",    orig_name="orig_font_chillround.ttf",
         first_source=[os.path.join(FONT_DIR, "font_chillround.ttf")], label="ChillRound 圆体"),
    dict(key="zhiyongdatong", dst="font_zhiyongdatong.ttf", orig_name="orig_zhiyongdatong.ttf",
         first_source=[os.path.join(UPLOAD_FONTS, "ZhiyongDatongFont.ttf")], label="志勇大同体"),
    dict(key="lemibobo",      dst="font_lemibobo.ttf",      orig_name="orig_lemibobo.ttf",
         first_source=[os.path.join(UPLOAD_FONTS, "乐米波波体.ttf")], label="乐米波波体"),
]

# ========================= 压缩档位 =========================
# grid：坐标量化格距（字体单位；这批字体 upem=1000）。
#       nova 6（1080x2400 / density≈2.6）上 14sp 正文实际约 36px 高，
#       grid=10 时定位误差 ≤ 5 单位 ≈ 0.18px，肉眼分辨不出；grid=14 ≈ 0.25px。
# tol ：删点允许的最大形状偏离（字体单位）。超过一点都不删，保证"砍点不砍形"。
# r1：R1（弧退化成弦）的倍率。二次曲线偏离弦 = 控制点距/2，所以 r1=2 时
#      实际形状误差仍是 tol；r1=1 更保守（弧线不易被拉平、圆体不会起棱角）。
#      实测把 2.0 降到 1.0 只多花约 1% 体积（2.30MB→2.32MB），但 64px IoU
#      从 0.863/0.909 提到 0.875/0.924，肉眼可见的差别消失了 —— 这笔交易划算，
#      所以直接固化成默认值；想要更极致的体积可再加 --r1-scale 2 吃回去。
TIERS = {
    "fine":     dict(grid=6,  tol=6,  r1=1.0),
    "standard": dict(grid=10, tol=10, r1=1.0),
    "extreme":  dict(grid=14, tol=18, r1=1.0),
    # ultra 仅作"还剩多少油水"的探针，不默认：量化误差到 0.5px 级，小字号会开始糊。
    "ultra":    dict(grid=20, tol=26, r1=1.0),
}

# ========================= 保留字符集 =========================
# CORE（必显示，任何档位都不许砍）：缺一个就是界面/歌词出豆腐块或半角错乱。
# HANS（汉字覆盖集）：压缩力度的主旋钮，默认 GB2312 一级字 3755。

def _rng(lo, hi):
    """码点区间展开成字符集合（写在代码里而不是字面串里，避免 '　-〿' 这种"看着像区间其实是 3 个字符"的坑）"""
    return {chr(c) for c in range(lo, hi + 1) if not (0xFFF9 <= c <= 0xFFFB)}

CORE_ASCII = _rng(0x20, 0x7E)                     # 95 个可见 ASCII：英文歌手名、00:12 时间轴、%/&# 等
CORE_LATIN1 = _rng(0xA0, 0xFF)                    # é ü ñ © ® ° ± ½ ¥ 等：外文名与货币，Android 也常用它做降级渲染
# 中文标点/全角：歌词 LRC 里绝大多数标点用的是**全角**码点（，．！？（）～／），
# 只给 U+3000 段不够，FF01-FF5E 也必须留，否则歌词标点变豆腐块。
CORE_CJK_PUNCT = _rng(0x3000, 0x303F)             # 、。〃〈〉《》「」『』【】〝〞〟
CORE_FULLWIDTH = _rng(0xFF01, 0xFF5E)             # ！＂＃…～ 全角 ASCII（含全角数字字母）
CORE_GENERAL_PUNCT = set(
    "‘’“”„‟…′″‴—–――·‧∙・"
    "«»‹›™ ©®℗℠"
    "±×÷≈≠≤≥∞√∑∏∫"
    "⁰¹²³⁴⁵⁶⁷⁸⁹½¼¾"
    "†‡‰※‖"
    "←↑→↓↔↕⇅⇆⇒"
    "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮"           # 榜单/歌单序号
    "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ"                    # 罗马数字（专辑序号）
    "♩♪♫♬♭♮♯"                            # 音乐符号
    "★☆✓✔✕✖✦✧"                          # 收藏/评分/勾选
    "○●◎□■▢△▲▽▼◇◆▱▭"                    # 几何符号（进度条/单选样式）
    "♥♡♠♣♦"                              # 花色符号
    "⒈⒉⒊─│┌┐└┘├┤┬┴┼"                    # 制表线（分隔符文案）
    "　 、"
)
# 常见姓氏 / 人名 / 地名用字：GB2312 一级字按拼音排列，"低频但高频作姓名"的字（酆宓郗羿…）
# 反而落在二级字里。歌手名、用户名、歌单名撞上就是风格跳字，所以点名列进来（约 300 字，成本很小）。
CORE_NAMES = set(
    "钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章"
    "云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安"
    "常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋"
    "茅庞熊纪舒屈项祝董梁杜阮蓝闵季贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍"
    "虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣"
    "荀羊於惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊"
    "轩晗煜淼焱堃犇骉赟玥珩甯翀彧旻昊睿霖泽涵梓晨悦妍琪欣怡俊杰涛静丽娜娟敏伟强磊"
    "军洋勇艳明超秀霞兰宏华建文辉力腾鸿翔龙辰旭阳默白墨苏杭津沪蓉滇頔喆镕昇昊曦"
)
# 音乐 App 业务字：乐器/曲风/音质等场景文案，避免"源码没写过但运营文案会用"的字缺字
CORE_MUSIC = set("吉他鼓键盘弦管号笛笙箫琵琶琴萨克斯贝斯提琴琴键节拍旋律节奏和声"
                 "音色音质无损高清杜比空间音频环绕重低音民谣电子嘻哈摇滚说唱古典爵士蓝调"
                 "专辑单曲专辑封面歌词歌手歌星乐队组合现场录音棚混音母带")

# 可选组（默认关）：日文假名。日文歌名/歌手名会用到，但 179 个字形的成本要单算，
# 想要就 --extra kana 打开；不加也不会豆腐块，Android 会回退系统字（只是风格跳）。
EXTRA_GROUPS = {
    "kana": _rng(0x3041, 0x309F) | _rng(0x30A0, 0x30FF),
}


def gb2312_level1():
    """
    GB2312 一级汉字 = 3755 个（区 16-55 × 位 1-94，含空洞），即国标"常用字"。
    为什么用它，而不是我手敲 3500 字：
      1) 国标认定，比手写列表准且不遗漏；3500 常用字表基本被它包住；
      2) 可用编码算法枚举，零外部依赖、任何机器都能复现；
      3) 真正省的地方在于**丢掉二级字 3008 个**（GB2312 全集 6763 -> 3755），
         而现有两款线上字体就是按 GB2312 全集裁的 —— 这一刀直接砍掉 44% 汉字字形。
    """
    s = set()
    for qu in range(16, 56):
        for wei in range(1, 95):
            try:
                ch = bytes([qu + 0xA0, wei + 0xA0]).decode("gb2312")
            except Exception:
                continue              # 区里有空洞，解不出就跳
            if "\u4e00" <= ch <= "\u9fff":
                s.add(ch)
    return s


def repo_corpus_hanzi():
    """
    更狠的汉字档：只保留"这个仓库里真实出现过的汉字"（源码含注释 + 更新说明 md + docs/）。
    为什么给这个档：GB2312 一级字 3755 里，有一大半在"歌名/歌词/界面文案"里极少出现；
    如果连歌词都接受"缺字回退系统字"（Android 不会变豆腐块，只是那一个字风格跳），
    这一档能再省 ~40% 体积。默认**不开**，只在包体实在压不住时用。
    """
    chars = set()
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in ("build", ".git", ".gradle", "node_modules",
                                                "font_originals", "__pycache__")]
        for fn in files:
            if not fn.endswith((".kt", ".md", ".json", ".xml", ".txt", ".html")):
                continue
            p = os.path.join(base, fn)
            try:
                t = open(p, encoding="utf-8").read()
            except (OSError, UnicodeDecodeError):
                continue
            for c in t:
                if "\u3400" <= c <= "\u9fff":
                    chars.add(c)
    return chars

# ---------- 从 App 源码抽"真实会用到的字符" ----------
# 这是"砍得狠又不出现豆腐块"的关键：源码字面量就是最小必显示集。
# 只扫会进 APK 的源：app/src/main 下的 *.kt、res/**/*.xml（含 strings.xml）、
# res/html、assets/*.json（default_settings.json 里的键名/中文值）。
LINE_COMMENT = re.compile(r'^\s*//.*$', re.M)
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
KT_LITERAL = re.compile(
    r'"""(?P<t3>.*?)"""'                                 # 三引号（HTML/JSON 模板）
    r'|"(?P<td>[^"\\\n]*(?:\\.[^"\\\n]*)*)"'             # 双引号串（含 \uXXXX、$插值）
    r"|'(?P<ts>[^'\\\n]*(?:\\.[^'\\\n]*)*)'",            # 单引号串 / char 字面量
    re.S,
)
XML_ESC = {
    "&lt;": "<", "&gt;": ">", "&amp;": "&", "&quot": '"',
    "&#10;": "\n", "&#9;": "\t", "&#160;": "\u00A0",
}


def unescape(s):
    """还原 \\uXXXX / \\n 等 Kotlin 转义、XML 实体；\\uXXXX 不还原就会漏掉硬编码符号"""
    s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)
    for a, b in (("\\n", "\n"), ("\\t", "\t"), ("\\r", "\r"), ('\\"', '"'),
                 ("\\'", "'"), ("\\\\", "\\")):
        s = s.replace(a, b)
    for a, b in XML_ESC.items():
        s = s.replace(a, b)
    # \u2460 之类的"源码里写成十进制码点"的少数情况：交给下面 hex 规则兜住即可，不再猜。
    return s


def strip_comments(text):
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def chars_from_kotlin(text):
    out = set()
    body = strip_comments(text)
    for m in KT_LITERAL.finditer(body):
        for g in ("t3", "td", "ts"):
            s = m.group(g)
            if s is None:
                continue
            # ${expr} 里的表达式不会直接显示（显示的是它的值），把 $ 变量名剔掉，
            # 省得把 "song.title" 这种标识符字符算进保留集。
            s = re.sub(r"\$\{[^}]*\}", "", s)
            s = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "", s)
            out.update(unescape(s))
    return out


def chars_from_xml(text):
    """strings.xml / 其它 res xml：属性值和文本节点都可能是用户可见文案"""
    out = set()
    for m in re.finditer(r'>([^<>]+)<', text):
        out.update(unescape(m.group(1)))
    for m in re.finditer(r'"([^"]*)"', text):
        out.update(unescape(m.group(1)))
    return out


def iter_source_texts():
    for base, dirs, files in os.walk(SRC_DIR):
        dirs[:] = [d for d in dirs if d not in ("build", ".git")]
        for fn in sorted(files):
            p = os.path.join(base, fn)
            rel = os.path.relpath(p, SRC_DIR).replace(os.sep, "/")
            if fn.endswith(".kt"):
                kind = "kt"
            elif fn.endswith(".xml") and rel.startswith("res/"):
                kind = "xml"
            elif fn.endswith((".json", ".html", ".htm")) and (
                    rel.startswith("assets/") or rel.startswith("res/html/")):
                kind = "raw"
            else:
                continue
            try:
                yield fn, kind, open(p, encoding="utf-8").read()
            except (OSError, UnicodeDecodeError):
                continue


def collect_app_chars():
    """App 源码字面量用到的全部字符（含中文），并给出统计用于交付说明"""
    chars, per_file = set(), {}
    for fn, kind, text in iter_source_texts():
        if kind == "kt":
            cs = chars_from_kotlin(text)
        elif kind == "xml":
            cs = chars_from_xml(text)
        else:
            cs = set(unescape(text))
        chars |= cs
        if cs:
            per_file[fn] = len(cs)
    return chars, per_file


def build_charset(hanzi="gb1", extras=()):
    core = set(CORE_ASCII) | CORE_LATIN1 | CORE_CJK_PUNCT | CORE_FULLWIDTH \
        | CORE_GENERAL_PUNCT | CORE_NAMES | CORE_MUSIC
    for k in extras:
        core |= EXTRA_GROUPS[k]
    app, per_file = collect_app_chars()
    # 只留可打印字符 + 空格/全角空格：控制字符（\r \n \x00 等）不需要字形，
    # 塞进 cmap 只会多出无谓的分段；同时过滤后的 app 集才能用于"100% 命中"断言。
    def printable(c):
        return len(c) == 1 and (c.isprintable() or c in (" ", "\u3000"))
    app = {c for c in app if printable(c)}
    core |= app
    core = {c for c in core if printable(c)}
    hans = {"gb1": gb2312_level1, "corpus": repo_corpus_hanzi, "none": lambda: set()}[hanzi]()
    return core, hans, app, per_file


# ========================= 第 3 步：pyftsubset =========================
# 参数选择理由（四款字体实测都是 TrueType glyf，无 CFF、无 kern 表依赖）：
#   --layout-features=''      丢掉所有 OpenType 特性 -> GSUB/GPOS/GDEF 整表消失。
#                             四款字体的连字/字距都是"锦上添花"（大同体 GSUB 只有 84B，
#                             childfun GPOS 3.3KB），界面/歌词按普通码点渲染，不需要它们。
#   --drop-tables+=...        DSIG（Android 不校验签名）、vhea/vmtx（竖排度量，App 不渲染竖排）、
#                             gasp（点阵像素提示）、BASE（基线）、cvt /fpgm/prep（指令 hinting，
#                             FreeType 用 autohint 就够）、hdmx/LTSH/VDMX/PCLT（打印机遗留）。
#                             注意 cvt 的真名是 "cvt " 带一个空格，写成 cvt 匹配不到。
#   --no-glyph-names          post 表从格式 2（逐字形名字）退成格式 3（无名）：
#                             大同体 post 原表 73,800B 全是浪费。
#   --no-notdef-glyph         不生成 .notdef 轮廓（缺字由 Android 回退系统字，见交付说明）。
#   --no-hinting              丢字形指令；--desubroutinize 只在 CFF 字体上有意义，留着无副作用。
#   --name-IDs=1,2,3,4,6      只留家族/子家族/唯一 ID/全名/PostScript 名；版权、说明、许可证
#                             条目 Android 用不到（许可义务由仓库根 NOTICE 文件承担）。
#   --no-recalc-timestamp     时间戳清零，保证同参数重跑产出字节一致（可复跑/可 diff）。
DROP_TABLES = ["DSIG", "GSUB", "GPOS", "GDEF", "BASE", "vhea", "vmtx", "gasp", "hdmx",
               "LTSH", "VDMX", "PCLT", "MPET", "EBLC", "EBDT", "EBSC", "ACRN", "JSTF",
               "LTRA", "LTRM", "SILT", "MATH", "fpgm", "prep", "cvt ", "CBLC", "CBDT",
               "sbix", "SVG ", "COLR", "CPAL", "FFTM", "TSIC", "TTXc"]
def run_subset(src, dst, keep_chars):
    """
    用 fontTools 的 Python API 裁剪（不用 CLI 的 --text-file）。
    为什么：--text-file 会**按行 splitlines 并跳过空白**，实测把 U+0020 空格、U+3000 全角空格
    整块漏掉（断言直接抓到：四款字体的空格全没了）。
    populate(unicodes=[...]) 是按码点精确投喂，一个字符都不会被解析器吃掉。
    """
    font = TTFont(src)
    o = Options()
    # hasattr 保护：换了 fontTools 版本、某个开关被改名/删掉时，脚本照样能跑（不会整批失败）
    def _set(**kw):
        for k, v in kw.items():
            if hasattr(o, k):
                setattr(o, k, v)
    _set(
        # 丢所有 OpenType 特性 => GSUB/GPOS/GDEF 整表消失（这些字体的连字/字距非必需）
        layout_features=[],
        # 明确不要的表（见本节顶部注释）；API 下 drop_tables 是"确定删除表"清单
        drop_tables=list(DROP_TABLES),
        glyph_names=False,          # post 退化成格式 3，省掉逐字形名字（大同体 73,800B）
        notdef_glyph=True,          # 保留 .notdef 空字形（配合 recommended_glyphs，Android 缺字回退更稳）
        notdef_outline=False,
        # recommended_glyphs=True：强制保留 .notdef / null / **space** 这类"推荐字形"。
        # 之前设 False 时，空格（无轮廓、0  contours）会被 subsetter 当无用字形剪掉，
        # 结果整个 App 的空格掉字（断言抓到了 U+0020 丢失）。空字形不占体积。
        recommended_glyphs=True,
        hinting=False,             # 不保留字形指令
        desubroutinize=True,       # 仅 CFF 有意义；TrueType 下无副作用
        name_IDs=[1, 2, 3, 4, 6],  # 只留家族/子家族/唯一 ID/全名/PostScript 名
        recalc_bounds=False,       # glyf 规约阶段统一重算
        recalc_timestamp=False,    # 时间戳固定 => 同参数重跑产物字节一致，可 diff 可复现
        legacy_cmap=True,          # 保留 format4 子表，Android 老机型 cmap 解析最稳
        unicode=True,
    )
    ss = Subsetter(options=o)
    ss.populate(unicodes=[ord(c) for c in keep_chars])
    ss.subset(font)
    font.save(dst)


# ========================= 第 4 步：glyf 规约 =========================
# pyftsubset 只能"少放字形"，不能"字形里少放点"，而这四款字体点数极尽奢侈：
# 平均每字 50（childfun）~ 250（乐米波波）个轮廓点，专业手描一个汉字只要 60-100。
# 奢侈的点全落在同一条直线上、或控制点几乎贴着弦 —— 删掉它们字形不变，字节数线性下降
# （glyf 占总体积 97%+，实测 2.9B/点）。
# 规则（每条都以"形状偏离 ≤ tol 字体单位"为门槛，超一点都不删；量化同理 ≤ grid/2）：
#   R0 量化后相邻两点重合           -> 删
#   R1 on-off-on，控制点到弦距 ≤ 2*tol -> 删（二次贝塞尔曲线偏离弦的最大距离 = 控制点距/2，
#      所以阈值放宽到 2*tol 才恰好等价于"形状误差 ≤ tol"；这一条省点最多）
#   R2 off-on-off，on 点 ≈ 两 off 中点 -> 删（TrueType 规范里相邻两个 off 点自动生成隐式 on 点，
#      其位置正是中点，删掉后曲线形状不变、轮廓依旧合法）
#   R3 on-on-on 三点共线（中点偏离 ≤ tol）-> 删
#   R4 两个连续 off 点重合（距离 ≤ tol）-> 删
# 不做的事（有意为之，避免"压缩把字压坏"）：
#   - 不做布尔化简/去重叠（会改变笔画形状，且多数渲染器靠 non-zero 规则本来就画对）；
#   - 不删小轮廓：实测四款字体的小面积轮廓个位数（是 丶、。 等真笔画，不是扫描噪点）；
#   - 不动 advance width（hmtx）：字宽一变，全局排版会跟着抖。
def _seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / l2
    if t < 0:
        t = 0.0
    elif t > 1:
        t = 1.0
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def _glyph_to_contours(g):
    out, start = [], 0
    for end in g.endPtsOfContours:
        out.append([(int(g.coordinates[i][0]), int(g.coordinates[i][1]),
                     bool(g.flags[i] & ON_CURVE)) for i in range(start, end + 1)])
        start = end + 1
    return out


def _contours_to_glyph(g, conts, glyf):
    coords, flags, ends = [], [], []
    for c in conts:
        for x, y, on in c:
            coords.append([x, y])
            flags.append(1 if on else 0)      # 只保留 on-curve 位；delta 编码由 fontTools 重新优化
        ends.append(len(coords) - 1)
    g.coordinates = GlyphCoordinates(coords)
    g.flags = bytearray(flags)
    g.endPtsOfContours = ends
    g.numberOfContours = len(ends)
    if ends:
        g.recalcBounds(glyf)
    else:
        g.xMin = g.yMin = g.xMax = g.yMax = 0
    return len(coords)


def _simplify_closed(pts, grid, tol, r1=2.0):
    """单条闭合轮廓：量化 + 冗余点删除。用双向链表 + 待判栈，避免每删一点就重扫全轮廓。"""
    n = len(pts)
    if grid > 1:
        xs = [round(x / grid) * grid for (x, y, on) in pts]
        ys = [round(y / grid) * grid for (x, y, on) in pts]
    else:
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
    on = [p[2] for p in pts]
    if n <= 3:
        return [(xs[i], ys[i], on[i]) for i in range(n)]
    prev = list(range(-1, n - 1)); prev[0] = n - 1
    nxt = list(range(1, n + 1)); nxt[-1] = 0
    alive = [True] * n
    cnt = n
    stack = list(range(n))
    while stack and cnt > 3:
        i = stack.pop()
        if not alive[i]:
            continue
        p, q = prev[i], nxt[i]
        xi, yi, oi = xs[i], ys[i], on[i]
        xp, yp, op = xs[p], ys[p], on[p]
        xq, yq, oq = xs[q], ys[q], on[q]
        drop = False
        if xi == xp and yi == yp:                                       # R0
            drop = True
        elif op and not oi and oq:                                      # R1
            drop = _seg_dist(xi, yi, xp, yp, xq, yq) <= r1 * tol
        elif not op and oi and not oq:                                  # R2
            drop = math.hypot(xi - (xp + xq) / 2.0, yi - (yp + yq) / 2.0) <= tol
        elif op and oi and oq:                                          # R3
            drop = _seg_dist(xi, yi, xp, yp, xq, yq) <= tol
        elif not op and not oi:                                         # R4
            drop = math.hypot(xi - xp, yi - yp) <= tol
        if drop:
            alive[i] = False
            nxt[p] = q; prev[q] = p
            cnt -= 1
            stack.append(p); stack.append(q)      # 邻居的邻域变了，回头重判
    return [(xs[i], ys[i], on[i]) for i in range(n) if alive[i]]


def slim_glyf(path, grid, tol, r1=2.0):
    """就地重写 glyf。返回 (before 点数, after 点数, 复合字形数)"""
    f = TTFont(path)
    glyf = f["glyf"]
    before = after = comp = rescued = 0
    xmin = ymin = 10 ** 9
    xmax = ymax = -10 ** 9
    for gn in f.getGlyphOrder():
        g = glyf[gn]
        if g.numberOfContours < 0:
            comp += 1          # 复合字形：本批只有 chillround 有 2 个，引用关系由 subset 维护，不动
            bb = (getattr(g, "xMin", None), getattr(g, "yMin", None),
                  getattr(g, "xMax", None), getattr(g, "yMax", None))
            if None not in bb:
                xmin = min(xmin, bb[0]); xmax = max(xmax, bb[2])
                ymin = min(ymin, bb[1]); ymax = max(ymax, bb[3])
            continue
        if g.numberOfContours == 0:
            continue
        conts = _glyph_to_contours(g)
        before += sum(len(c) for c in conts)
        had_ink = (g.xMax - g.xMin) > 0 or (g.yMax - g.yMin) > 0
        new = []
        for c in conts:
            s = _simplify_closed(c, grid, tol, r1)
            if len(s) >= 3:          # 少于 3 点闭不拢，整条扔掉（量化后塌成一个点的碎轮廓）
                new.append(s)
        # 保险丝：破折号「—」、顿号「、」这类细笔画，量化格距一旦大于笔画厚度，
        # 整个轮廓会被量化到 0 面积 -> 字形凭空消失（实测 ultra 档 chillround 的「—」IoU=0）。
        # 字形消失比多留几个点严重得多，所以这里对极少数细字退回原始轮廓，不做简化。
        if not new and had_ink:
            new = [list(c) for c in conts if len(c) >= 3]
            rescued += 1
        after += _contours_to_glyph(g, new, glyf)
        if new:
            xmin = min(xmin, g.xMin); xmax = max(xmax, g.xMax)
            ymin = min(ymin, g.yMin); ymax = max(ymax, g.yMax)
    if rescued:
        print(f"          [细笔画保险] {rescued} 个字形量化后会塌成空白，已退回原始轮廓")
    # 量化后整体坐标范围可能收缩，head 的字体级 bbox 要跟着改，否则渲染裁剪框偏大（不影响显示但字节不一致）
    if xmax >= xmin:
        head = f["head"]
        head.xMin, head.yMin, head.xMax, head.yMax = int(xmin), int(ymin), int(xmax), int(ymax)
    f.save(path)
    return before, after, comp


# ========================= 测量 / 校验 =========================
def cmap_of(path):
    f = TTFont(path, lazy=True)
    return set(f.getBestCmap().keys()), f


def deflate_size(path, level=9):
    """
    APK 内该条目的真实占用口径 = zip deflate 后的**裸 payload**（不含 zip 头）。
    aapt2 release 用 zlib deflate；这里用 raw deflate(wbits=-15) 精确对齐 zip 的 compress_size，
    并且已用线上 APK 的 res/7N.ttf、res/Z2.ttf 校准（见 --apk 输出）。
    """
    buf = open(path, "rb").read()
    co = zlib.compressobj(level, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY)
    return len(co.compress(buf) + co.flush())


def ensure_backups():
    """把原件收进 tools/font_originals/；已存在就绝不覆盖（幂等 + 防止把裁剪结果当原件备份）"""
    os.makedirs(ORIG_DIR, exist_ok=True)
    new = []
    for spec in FONTS:
        bk = os.path.join(ORIG_DIR, spec["orig_name"])
        if os.path.exists(bk):
            continue
        for cand in spec["first_source"]:
            if os.path.exists(cand):
                shutil.copy2(cand, bk)
                new.append(spec["label"])
                break
        else:
            raise SystemExit(f"找不到 {spec['label']} 的原件（{spec['first_source']}）；"
                             f"新字体请先解压到 {UPLOAD_FONTS}/")
    return new


def restore():
    """--restore：用 font_originals 的原件覆盖回 res/font（一键回退）"""
    for spec in FONTS:
        bk = os.path.join(ORIG_DIR, spec["orig_name"])
        dst = os.path.join(FONT_DIR, spec["dst"])
        if os.path.exists(bk):
            shutil.copy2(bk, dst)
            print(f"[回退] {spec['label']:14s} -> {dst}")
        else:
            print(f"[回退] 缺原件备份：{bk}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", default="extreme", choices=list(TIERS))
    ap.add_argument("--r1-scale", type=float, default=1.0,
                    help="R1（弧拉直）容差倍率，<1 更保真、>1 更省体积")
    ap.add_argument("--hanzi", default="gb1", choices=["gb1", "corpus", "none"],
                    help="汉字集：gb1=GB2312 一级字 3755（默认，含 3500 常用字）；"
                         "corpus=只留仓库真实用过的汉字（更狠，歌词缺字会回退系统字）；none=只留标点数字")
    ap.add_argument("--extra", default="", help=f"追加可选字符组，逗号分隔：{','.join(EXTRA_GROUPS)}")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report-only", action="store_true")
    ap.add_argument("--restore", action="store_true")
    ap.add_argument("--apk", default="", help="用真实 APK 校准 deflate 口径")
    ap.add_argument("--montage", default="", help="输出'原件 vs 裁剪'对比 PNG 目录（目检）")
    ap.add_argument("--keep-work", action="store_true", help="保留 /tmp 工作目录")
    args = ap.parse_args()

    if args.restore:
        restore(); return

    tp = TIERS[args.tier]
    grid, tol, r1 = tp["grid"], tp["tol"], tp.get("r1", 1.0) * args.r1_scale
    extras = tuple(x for x in args.extra.split(",") if x.strip())
    core, hans, app_chars, per_file = build_charset(hanzi=args.hanzi, extras=extras)
    keep = core | hans
    # cmap 的 key 是码点整数，这里同时准备一份码点版，供断言用（少这一步就会把"命中"算成 0）
    keep_cp = {ord(c) for c in keep}
    app_cp = {ord(c) for c in app_chars}
    app_cjk = {c for c in app_chars if ord(c) >= 0x2E80}
    print(f"[档位] {args.tier}: grid={grid} tol={tol} r1={r1:.2f}")
    print(f"[字符集] 保留 {len(keep)} = 必显示核心 {len(core)} + 汉字 {len(hans)}")
    print(f"[字符集] 核心构成：ASCII {len(CORE_ASCII)} / Latin-1 {len(CORE_LATIN1)} / "
          f"CJK 标点 {len(CORE_CJK_PUNCT)} / 全角 {len(CORE_FULLWIDTH)} / "
          f"通用标点符号 {len(CORE_GENERAL_PUNCT)} / 姓名用字 {len(CORE_NAMES)} / 业务字 {len(CORE_MUSIC)}")
    print(f"[字符集] App 源码字面量：{len(app_chars)} 字符（其中 CJK {len(app_cjk)}），"
          f"来自 {len(per_file)} 个文件；源码有、汉字集没有的补 {len(app_chars - core - hans)} 个")
    print(f"[字符集] 汉字口径：{args.hanzi} => {len(hans)} 字"
          + ("（GB2312 一级字，含 3500 常用字表；丢二级字 3008）" if args.hanzi == "gb1" else ""))

    if args.apk and os.path.exists(args.apk):
        # 校准：把线上 APK 里的 ttf 条目**解压出来**再按同口径 deflate，验证本机估算 = aapt 实测
        with zipfile.ZipFile(args.apk) as z, tempfile.TemporaryDirectory() as td:
            for i in z.infolist():
                if i.filename.endswith(".ttf"):
                    p = os.path.join(td, os.path.basename(i.filename))
                    open(p, "wb").write(z.read(i))
                    print(f"[校准] {os.path.basename(args.apk)} 内 {i.filename}: "
                          f"raw={i.file_size} aapt_deflate={i.compress_size} "
                          f"本机 L9={deflate_size(p, 9)} L6={deflate_size(p, 6)}")

    if args.report_only:
        for spec in FONTS:
            p = os.path.join(FONT_DIR, spec["dst"])
            if os.path.exists(p):
                print(f"[现状] {spec['label']:14s} raw={os.path.getsize(p):9d} "
                      f"deflate9={deflate_size(p):9d}")
        return

    newbk = ensure_backups()
    if newbk:
        print(f"[备份] 新收原件：{', '.join(newbk)} -> tools/font_originals/")

    work = "/tmp/_jun_font_work"
    os.makedirs(work, exist_ok=True)

    rows, fail = [], []
    for spec in FONTS:
        label = spec["label"]
        orig = os.path.join(ORIG_DIR, spec["orig_name"])
        dst = os.path.join(FONT_DIR, spec["dst"])
        tmp = os.path.join(work, spec["key"] + ".ttf")
        orig_size = os.path.getsize(orig)
        orig_defl = deflate_size(orig)
        orig_cmap, _ = cmap_of(orig)

        run_subset(orig, tmp, keep)               # 第 3 步
        subset_size = os.path.getsize(tmp)
        b, a, ncomp = slim_glyf(tmp, grid, tol, r1)   # 第 4 步
        final_size = os.path.getsize(tmp)
        new_cmap, _ = cmap_of(tmp)

        # ---- 断言 A：保留集里"原字体本来就有"的字，裁剪后必须 100% 还在（丢字就报错退出，绝不静默）----
        # 注意：cmap 的 key 是**码点整数**，keep/app 是**字符**，必须先 ord() 换算再比集合。
        promised = keep_cp & orig_cmap
        lost = sorted(promised - new_cmap)
        # ---- 断言 B：App 源码用到的字符，必须 100% 命中（这是"不许出现豆腐块"的硬线）----
        app_hit = app_cp & orig_cmap
        app_lost = sorted(app_hit - new_cmap)
        app_absent = sorted(app_cp - orig_cmap)    # 原字体本来就没有 -> 系统字回退，非本次裁剪造成
        # ---- 断言 C：字体能被重新解析（防止规约把 glyf 改坏成半个文件）----
        probe_cmap, probe_font = cmap_of(tmp)
        if not probe_cmap or probe_font["maxp"].numGlyphs != len(probe_font.getGlyphOrder()):
            fail.append(f"{label}: 裁剪后字体无法完整解析")
        fail += [f"{label}: 保留集字符丢失 {chr(ch)!r} U+{ch:04X}" for ch in lost]
        fail += [f"{label}: App 用到的字符丢失 {chr(ch)!r} U+{ch:04X}" for ch in app_lost]

        rows.append(dict(spec=spec, label=label, orig=orig, dst=dst, tmp=tmp,
                         orig_size=orig_size, orig_defl=orig_defl,
                         subset_size=subset_size, final_size=final_size,
                         defl9=deflate_size(tmp, 9), defl6=deflate_size(tmp, 6),
                         pts_b=b, pts_a=a, comp=ncomp,
                         promised=len(promised), kept=len(promised & new_cmap),
                         app=len(app_hit), app_kept=len(app_hit) - len(app_lost),
                         app_absent=len(app_absent), src_absent=len(keep_cp) - len(promised)))
        rows[-1]["app_absent_str"] = "".join(chr(c) for c in app_absent[:60])
        print(f"[{label}] 点数 {b}->{a} ({100*a/max(b,1):.0f}%) 复合 {ncomp} | "
              f"{orig_size}->{subset_size}->{final_size} B | deflate9 {orig_defl}->{rows[-1]['defl9']} | "
              f"保留字 {rows[-1]['kept']}/{rows[-1]['promised']} | "
              f"App 字 {rows[-1]['app_kept']}/{rows[-1]['app']}（原字体本就缺 {rows[-1]['app_absent']}）")
        if rows[-1]["app_absent"]:
            print(f"          原字体缺的 App 字（Android 会回退系统字，非本次裁剪造成）："
                  f"{rows[-1]['app_absent_str']}")

    if fail:
        print("\n!!!! 断言失败：已中止，res/font 未写回 !!!!")
        for m in fail[:60]:
            print("   -", m)
        sys.exit(2)

    if args.montage:
        make_montage(rows, args.montage)

    if not args.dry_run:
        for r in rows:
            shutil.copy2(r["tmp"], r["dst"])
        print(f"[写回] {len(rows)} 款字体已更新 -> app/src/main/res/font/")
    else:
        print("[dry-run] 未写回 res/font")
        for r in rows:
            print(f"          产物留在 {r['tmp']}")

    print("\n=== 压缩前后对比（deflate = APK 内实际占用，raw deflate 口径）===")
    print(f"{'字体':<15s}{'原始B':>10s}{'subset后B':>10s}{'规约后B':>10s}{'原始defl':>10s}"
          f"{'defl L9':>9s}{'defl L6':>9s}{'保留字':>8s}{'字体缺字':>9s}")
    for r in rows:
        print(f"{r['label']:<15s}{r['orig_size']:>10d}{r['subset_size']:>10d}{r['final_size']:>10d}"
              f"{r['orig_defl']:>10d}{r['defl9']:>9d}{r['defl6']:>9d}{r['kept']:>8d}{r['src_absent']:>9d}")
    s = lambda k: sum(r[k] for r in rows)
    print(f"{'合计':<15s}{s('orig_size'):>10d}{'':>10s}{s('final_size'):>10d}{s('orig_defl'):>10d}"
          f"{s('defl9'):>9d}{s('defl6'):>9d}")
    old2 = [r for r in rows if r['spec']['key'] in ('childfun', 'chillround')]
    ob = sum(r['orig_defl'] for r in old2)
    print(f"[结论] 现有两款原始 deflate={ob} -> 四款压缩后 deflate={s('defl9')} "
          f"({100*s('defl9')/ob:.0f}% of 两款原件，目标 ≤50%)")
    print("[断言] 保留集字符 100% 命中；App 源码字符 100% 命中 —— 通过")


def make_montage(rows, outdir):
    """'原件 vs 裁剪后'同屏对比图，肉眼可达校验用（nova 6 上 14sp≈36px，故按 36/64px 各出一行）"""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("[目检] 缺 Pillow，跳过 montage")
        return
    os.makedirs(outdir, exist_ok=True)
    s1 = "Jun音乐cloud 1234:57 ♪★，。！？、：；（）《》…—·"
    s2 = "播放收藏搜索每日推荐离线歌词字体设置周杰伦晴天故事的小黄花"
    s3 = "从出生那年最大的秋天飘着梧桐的思念在夏天你白衬衫干干净净"
    for r in rows:
        W = 1080
        img = Image.new("RGB", (W, 4 * 120), "white")
        d = ImageDraw.Draw(img)
        y = 6
        for px in (26, 36, 64):
            for path, tag in ((r["orig"], "原"), (r["tmp"], "裁")):
                try:
                    f = ImageFont.truetype(path, px)
                except Exception as e:
                    d.text((10, y), f"render fail {path}: {e}", fill="red"); continue
                d.text((10, y), f"{tag}{px}px ", fill=(150, 150, 150),
                       font=ImageFont.truetype(path, max(14, px // 2)))
                d.text((90, y), s1 if px == 26 else (s2 if px == 36 else s3), fill="black", font=f)
            y += px + 12
        img.save(os.path.join(outdir, f"montage_{r['spec']['key']}.png"))
    print(f"[目检] 对比图 -> {outdir}/montage_*.png")


if __name__ == "__main__":
    main()
