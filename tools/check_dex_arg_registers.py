#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_dex_arg_registers.py —— 检查 APK 里有没有「传参寄存器个数对不上」的坏指令。

背景（5.2featR3 的启动闪退）：
  dex 的 invoke-*/range 指令用一个 **8 位** 字段记录传参寄存器个数（上限 255）。
  PlayerUiState 曾经有 238 个属性，Kotlin 为 `state.copy(x = 1)` 生成的
  `PlayerUiState.copy$default` 需要 260 个 argument registers，D8 把它写成
  260 & 0xFF = 4，产出一条只传 4 个寄存器、目标却要 260 个的坏指令：
      7704 a267 0300     // invoke-static/range，reg_count=4，method@67a2 = copy$default
  javac/kotlinc、D8、R8、签名、安装全部一路绿灯，直到 ART 校验这个类才炸：
      java.lang.VerifyError: Verifier rejected class
      com.jun.musiccloud.player.PlayerViewModel: ... Rejecting invocation,
      expected 4 argument registers, method signature has 5 or more
  PlayerViewModel 是首页就要构造的类 → 点开 App 直接闪退，
  而且混淆版和「-dontoptimize 诊断版」完全一样（R8 不是元凶，是 D8/dex 编码上限）。

所以：发版前跑一遍这个脚本。它只读 APK，不动手机，几秒钟出结果。
  python3 tools/check_dex_arg_registers.py 安装包/xxx.apk
退出码 0 = 干净；1 = 发现坏指令（打印所在类/方法/偏移/目标）；2 = APK 读不了。

另外会打印「最大的一个调用需要 N 个 argument registers」，这就是余量指标：
N 越接近 255 越危险，超过 255 必炸。5.2featR3 拆完 PlayerExtras 之后实测
外层 133、内层 124，余量充足。

opcode / 宽度表不是手抄的：是从这份 APK 自己的 dexdump -d 反汇编里
统计「助记词 ↔ 首字节 ↔ 相邻指令偏移差」得来的，所以和这台 build-tools
产出的码流严格一致（校验过程见《5.2feat-更新说明.md》第 0.2 节末尾）。
"""

import struct
import sys
import zipfile

# 从 dexdump 实测得到的 code unit 宽度表（0x00 nop 之外的 0 表示变长/payload）
WIDTH = {
    0x00: 1, 0x01: 1, 0x02: 2, 0x03: 3, 0x04: 1, 0x05: 2, 0x06: 3, 0x07: 1,
    0x08: 2, 0x09: 3, 0x0a: 1, 0x0b: 1, 0x0c: 1, 0x0d: 1, 0x0e: 1, 0x0f: 1,
    0x10: 1, 0x11: 1, 0x12: 1, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3,
    0x18: 5, 0x19: 2, 0x1a: 2, 0x1b: 2, 0x1c: 2, 0x1d: 1, 0x1e: 1, 0x1f: 2,
    0x20: 2, 0x21: 1, 0x22: 2, 0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3, 0x27: 1,
    0x28: 1, 0x29: 2, 0x2a: 3, 0x2b: 3, 0x2c: 3, 0x2d: 2, 0x2e: 2, 0x2f: 2,
    0x30: 2, 0x31: 2, 0x32: 2, 0x33: 2, 0x34: 2, 0x35: 2, 0x36: 2, 0x37: 2,
    0x38: 2, 0x39: 2, 0x3a: 2, 0x3b: 2, 0x3c: 2, 0x3d: 2,
    0x44: 2, 0x45: 2, 0x46: 2, 0x47: 2, 0x48: 2, 0x49: 2, 0x4a: 2,
    0x4b: 2, 0x4c: 2, 0x4d: 2, 0x4e: 2, 0x4f: 2, 0x50: 2, 0x51: 2,
    0x52: 2, 0x53: 2, 0x54: 2, 0x55: 2, 0x56: 2, 0x57: 2, 0x58: 2,
    0x59: 2, 0x5a: 2, 0x5b: 2, 0x5c: 2, 0x5d: 2, 0x5e: 2, 0x5f: 2,
    0x60: 2, 0x61: 2, 0x62: 2, 0x63: 2, 0x64: 2, 0x65: 2, 0x66: 2, 0x67: 2,
    0x68: 2, 0x69: 2, 0x6a: 2, 0x6b: 2, 0x6c: 2, 0x6d: 2,
    0x6e: 3, 0x6f: 3, 0x70: 3, 0x71: 3, 0x72: 3,
    0x74: 3, 0x75: 3, 0x76: 3, 0x77: 3, 0x78: 3,
    0x7b: 1, 0x7c: 1, 0x7d: 1, 0x7e: 1, 0x7f: 1,
    0x80: 1, 0x81: 1, 0x82: 1, 0x83: 1, 0x84: 1, 0x85: 1, 0x86: 1, 0x87: 1,
    0x88: 1, 0x89: 1, 0x8a: 1, 0x8b: 1, 0x8c: 1, 0x8d: 1, 0x8e: 1, 0x8f: 1,
    0x90: 2, 0x91: 2, 0x92: 2, 0x93: 2, 0x94: 2, 0x95: 2, 0x96: 2, 0x97: 2,
    0x98: 2, 0x99: 2, 0x9a: 2, 0x9b: 2, 0x9c: 2, 0x9d: 2, 0x9e: 2, 0x9f: 2,
    0xa0: 2, 0xa1: 2, 0xa2: 2, 0xa3: 2, 0xa4: 2, 0xa5: 2, 0xa6: 2, 0xa7: 2,
    0xa8: 2, 0xa9: 2, 0xaa: 2, 0xab: 2, 0xac: 2, 0xad: 2, 0xae: 2, 0xaf: 2,
    0xb0: 1, 0xb1: 1, 0xb2: 1, 0xb3: 1, 0xb4: 1, 0xb5: 1, 0xb6: 1, 0xb7: 1,
    0xb8: 1, 0xb9: 1, 0xba: 1, 0xbb: 1, 0xbc: 1, 0xbd: 1, 0xbe: 1, 0xbf: 1,
    0xc0: 1, 0xc1: 1, 0xc2: 1, 0xc3: 1, 0xc4: 1, 0xc5: 1, 0xc6: 1, 0xc7: 1,
    0xc8: 1, 0xc9: 1, 0xca: 1, 0xcb: 1, 0xcc: 1, 0xcd: 1, 0xce: 1, 0xcf: 1,
    0xd0: 2, 0xd1: 2, 0xd2: 2, 0xd3: 2, 0xd4: 2, 0xd5: 2, 0xd6: 2, 0xd7: 2,
    0xd8: 2, 0xd9: 2, 0xda: 2, 0xdb: 2, 0xdc: 2, 0xdd: 2, 0xde: 2, 0xdf: 2,
    0xe0: 2, 0xe1: 2, 0xe2: 2,
}

# 3rc（invoke-*/range）：首字节 = op，次字节 = 传参寄存器个数（8 位！上限 255）
RANGE_OPS = {
    0x74: ('invoke-virtual/range', True),
    0x75: ('invoke-super/range', True),
    0x76: ('invoke-direct/range', True),
    0x77: ('invoke-static/range', False),
    0x78: ('invoke-interface/range', True),
}

PAYLOAD_IDENTS = {0x0100: 'packed-switch', 0x0200: 'sparse-switch', 0x0300: 'fill-array-data'}
PAYLOAD_OPS = {0x26: 'fill-array-data', 0x2b: 'packed-switch', 0x2c: 'sparse-switch'}


def uleb(d, o):
    r = 0
    s = 0
    while True:
        b = d[o]
        o += 1
        r |= (b & 0x7F) << s
        if not (b & 0x80):
            return r, o
        s += 7


class Dex:
    def __init__(self, blob, name):
        self.name = name
        self.b = blob
        if blob[:4] != b'dex\n':
            raise ValueError('%s 不是 dex' % name)
        # 段位置一律从 map_list 读，别按 header 里的字段顺序猜
        MAP = {0x0001: 'str', 0x0002: 'type', 0x0003: 'proto',
               0x0004: 'field', 0x0005: 'meth', 0x0006: 'cls',
               0x0007: 'map'}
        map_off = struct.unpack_from('<I', blob, 0x34)[0]
        cnt = struct.unpack_from('<I', blob, map_off)[0]
        got = {}
        for i in range(cnt):
            typ, _, sz, off = struct.unpack_from('<HHII', blob, map_off + 4 + i * 12)
            if typ in MAP:
                got[MAP[typ]] = (sz, off)
        for k in ('str', 'type', 'proto', 'field', 'meth', 'cls'):
            if k not in got:
                raise ValueError('%s 缺 %s 段' % (name, k))
        self.str_size, self.str_off = got['str']
        self.type_size, self.type_off = got['type']
        self.proto_size, self.proto_off = got['proto']
        self.field_size, self.field_off = got['field']
        self.meth_size, self.meth_off = got['meth']
        self.cls_size, self.cls_off = got['cls']

    def string(self, idx):
        o = struct.unpack_from('<I', self.b, self.str_off + idx * 4)[0]
        _, o = uleb(self.b, o)           # utf16 size
        e = self.b.index(b'\0', o)
        return self.b[o:e].decode('utf-8', 'replace')

    def type_desc(self, idx):
        if idx < 0 or idx >= self.type_size:
            return None
        return self.string(struct.unpack_from('<I', self.b, self.type_off + idx * 4)[0])

    def proto(self, idx):
        """参数要占多少个 argument registers。

        用 shorty 数，不去啃 type_list：shorty 的第 1 个字符是返回值，
        其后每个字符对应一个参数，J / D 占 2 个 slot，其余占 1 个 —— 这也正是
        ART 校验器算 need 的办法，和 dexdump 打印出来的方法签名一一对得上。
        （实测：按 type_list stride=4 读会串位，shorty 才稳。）
        """
        shorty, ret, params_off = struct.unpack_from('<3I', self.b, self.proto_off + idx * 12)
        sh = self.string(shorty)
        slots = sum(2 if c in ('J', 'D') else 1 for c in sh[1:])
        return slots, 2 if sh[:1] in ('J', 'D') else 1

    def method(self, idx):
        """返回 (class_desc, name, 参数 slot 数)"""
        o = self.meth_off + idx * 8
        cidx, pidx = struct.unpack_from('<HH', self.b, o)
        name = self.string(struct.unpack_from('<I', self.b, o + 4)[0])
        slots, _ = self.proto(pidx)
        return self.type_desc(cidx), name, slots

    def methods(self):
        """yield (class_desc, method_idx, code_item_offset)

        class_data_item 的严格顺序：
          uleb static_fields_size, uleb instance_fields_size,
          uleb direct_methods_size, uleb virtual_methods_size,
          encoded_field[static], encoded_field[instance],
          encoded_method[direct], encoded_method[virtual]
        encoded_field = 2 个 uleb（field_idx_diff, access_flags）
        encoded_method = 3 个 uleb（method_idx_diff, access_flags, code_off）
        """
        for i in range(self.cls_size):
            cdef = self.cls_off + i * 32
            cidx = struct.unpack_from('<I', self.b, cdef)[0]
            cd_off = struct.unpack_from('<I', self.b, cdef + 24)[0]
            if not cd_off:
                continue
            cls = self.type_desc(cidx)
            o = cd_off
            static_n, o = uleb(self.b, o)
            inst_n, o = uleb(self.b, o)
            direct_n, o = uleb(self.b, o)
            virtual_n, o = uleb(self.b, o)
            for _ in range(static_n + inst_n):        # 先跳过一个都不能少的 encoded_field
                _, o = uleb(self.b, o)
                _, o = uleb(self.b, o)
            mid = 0
            for _ in range(direct_n):
                d, o = uleb(self.b, o)
                mid += d
                _, o = uleb(self.b, o)                # access_flags
                c, o = uleb(self.b, o)                # code_off
                if c:
                    yield cls, mid, c
            mid = 0
            for _ in range(virtual_n):
                d, o = uleb(self.b, o)
                mid += d
                _, o = uleb(self.b, o)
                c, o = uleb(self.b, o)
                if c:
                    yield cls, mid, c


def payload_len(code, off):
    """code[off] 已是 payload ident，返回 (类型, 下一个 code unit 的位置)。
    长度公式按这份 APK 的 dexdump -d 实测核对过（724/724 全对）：
      packed-switch-payload  : ident, size, first_case(int32), target[int32]*size  -> 4 + 2*size
      sparse-switch-payload  : ident, size, (key(int32) + target(int32))*size       -> 2 + 4*size
      fill-array-data-payload: ident, element_width, element_size(int32), data      -> 4 + ceil(w*s/2)
    注意这份码流里 switch 的 target 是 int32（2 个 code unit），不是老 spec 的 short。"""
    ident = struct.unpack_from('<H', code, off * 2)[0]
    size = struct.unpack_from('<H', code, (off + 1) * 2)[0]
    if ident == 0x0100:
        return 'packed-switch', off + 4 + 2 * size
    if ident == 0x0200:
        return 'sparse-switch', off + 2 + 4 * size
    cnt = struct.unpack_from('<I', code, off * 2 + 4)[0]
    return 'fill-array-data', off + 4 + (size * cnt + 1) // 2


def walk(dex, code, regs, sink):
    """按 code unit 线性走一条方法的码流；payload 正确跳过；每条 3rc 调用 sink。

    payload（switch 表 / 数组数据）不一定紧跟在触发它的指令后面 —— D8 把它们统一
    堆在码流尾部，所以「顺着引用跳过去」会把中间的真指令整段跳丢。
    正确走法：线性扫，ident 就是数据块按长度跳过；switch/array 指令本身只占 3 个
    code unit；走完之后再回头核对每条引用落在哪个数据块上。
    """
    n = len(code) // 2
    off = 0
    payloads = {}
    refs = []
    while off < n:
        u = struct.unpack_from('<H', code, off * 2)[0]
        if u in PAYLOAD_IDENTS:            # ident 低字节是 0x00，必须比 opcode 先判
            kind, nxt = payload_len(code, off)
            if nxt > n:
                raise ValueError('%s 在偏移 0x%x 处越出码流' % (kind, off * 2))
            payloads[off] = kind
            off = nxt
            continue
        op = u & 0xFF
        if op in RANGE_OPS:
            name, has_recv = RANGE_OPS[op]
            count = u >> 8                            # 8 bit 字段 → 最大 255
            target = struct.unpack_from('<H', code, (off + 1) * 2)[0]
            vstart = struct.unpack_from('<H', code, (off + 2) * 2)[0]
            sink(off, name, count, target, vstart, regs, has_recv)
            off += 3
            continue
        if op in (0x26, 0x2b, 0x2c):                  # 普通 3 unit 指令，数据块在别处
            refs.append((off, PAYLOAD_OPS[op],
                         off + struct.unpack_from('<i', code, (off + 1) * 2)[0]))
            off += 3
            continue
        if op == 0x00:                                # 对齐用的 nop 填充
            off += 1
            continue
        width = WIDTH.get(op)
        if width is None:
            raise ValueError('未知 opcode 0x%02x（偏移 0x%x）' % (op, off * 2))
        off += width
    for off, want, tgt in refs:
        got = payloads.get(tgt)
        if got is None:                               # payload 前可能有 nop 填充
            p = tgt
            while p < n and p not in payloads:
                p += 1
            got = payloads.get(p)
        if got != want:
            raise ValueError('偏移 0x%x 的 %s 指向 0x%x，那里是 %s'
                             % (off * 2, want, tgt, got))


def check(dex):
    bad = []
    worst = [0, None]
    scanned = 0
    for cls, midx, coff in dex.methods():
        scanned += 1
        regs, ins, outs, tries = struct.unpack_from('<4H', dex.b, coff)
        insns_size = struct.unpack_from('<I', dex.b, coff + 12)[0]
        code = dex.b[coff + 16: coff + 16 + insns_size * 2]
        caller = dex.method(midx)[1]

        def sink(off, name, count, target, vstart, regs_, has_recv):
            tcls, tname, tslots = dex.method(target)
            need = tslots + (1 if has_recv else 0)
            if count != need:
                bad.append('%s!%s 偏移 0x%x: %s 只传 %d 个寄存器，目标 %s!%s 需要 %d 个'
                           % (cls, caller, off * 2, name, count, tcls, tname, need))
            if need > 255:
                bad.append('%s!%s 偏移 0x%x: 调 %s!%s 需要 %d 个 argument registers，'
                           '已超出 dex 的 8 位上限 255 —— 这条指令必然被截断'
                           % (cls, caller, off * 2, tcls, tname, need))
            if vstart + count > regs_:
                bad.append('%s!%s 偏移 0x%x: v%d 起 %d 个寄存器，超出该方法 %d 个寄存器'
                           % (cls, caller, off * 2, vstart, count, regs_))
            if need > worst[0]:
                worst[0] = need
                worst[1] = '%s!%s → %s!%s' % (cls, caller, tcls, tname)

        walk(dex, code, regs, sink)
    return bad, worst, scanned


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    apk = argv[1]
    try:
        z = zipfile.ZipFile(apk)
        dexes = sorted(n for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
    except Exception as e:
        print('读不了 %s: %s' % (apk, e))
        return 2
    all_bad = []
    worst = [0, None]
    total_scanned = 0
    for n in dexes:
        try:
            d = Dex(z.read(n), n)
            bad, w, scanned = check(d)
        except Exception as e:
            print('%s 解析失败: %s' % (n, e))
            return 2
        all_bad += ['%s: %s' % (n, b) for b in bad]
        if w[0] > worst[0]:
            worst = w
        total_scanned += scanned
    print('APK: %s' % apk)
    print('dex: %d 个，扫描 %d 个带代码的方法' % (len(dexes), total_scanned))
    print('最大的一个调用需要 %d 个 argument registers（上限 255，余量 %d）：%s'
          % (worst[0], 255 - worst[0], worst[1] or '-'))
    if all_bad:
        print('✗ 发现 %d 处坏指令：' % len(all_bad))
        for b in all_bad[:20]:
            print('   ', b)
        if len(all_bad) > 20:
            print('    …（其余 %d 条略）' % (len(all_bad) - 20))
        return 1
    print('✓ 没有参数个数对不上的 invoke-*/range 指令')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
