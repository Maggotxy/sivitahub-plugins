#!/usr/bin/env python3
# scripts/asset_fingerprint.py —— 解码后像素的 SHA-256 指纹（W12-A1）
#
# 等价实现 resource-pack/tools/PngFingerprint.java（本仓没有 JDK，CI 只有 python3）：
# 摘要输入 = 大端 width(4) + height(4) + 逐像素大端 ARGB(4)，逐行 y、逐列 x。
# 为什么不用「PNG 文件字节」的 SHA-256：PNG 编码器会改元数据 / 滤波方式 /
# 压缩率，同一张图重新导出就会换哈希，门禁天天假红。这里算的是**解码后的像素**，
# 只对「画面真的变了」敏感。
#
# 解码范围：8 bit 非隔行 PNG，颜色类型 0/2/3/4/6（含调色板与灰度）。
# 灰度 / 调色板图需要 tRNS 才能真正还原 alpha，本脚本按规范实现；
# 其它形态（16 bit / 隔行 Adam7）显式报错，不静默降级成错指纹。
#
# 用法：python3 scripts/asset_fingerprint.py <a.png> [b.png ...]
# 输出：每行 "<宽>x<高>:<sha256hex> [路径]"

import hashlib
import struct
import sys
import zlib

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def _unfilter(raw, width, height, channels, bit_depth):
    """反 PNG 行滤波（spec 5.2），返回逐行原始字节。"""
    stride = (width * channels * bit_depth + 7) // 8
    bpp = max(1, (channels * bit_depth) // 8)
    out = []
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if filter_type == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                up = previous[i]
                up_left = previous[i - bpp] if i >= bpp else 0
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                if pa <= pb and pa <= pc:
                    pred = left
                elif pb <= pc:
                    pred = up
                else:
                    pred = up_left
                line[i] = (line[i] + pred) & 0xFF
        elif filter_type != 0:
            raise ValueError("未知 PNG 行滤波类型 %d" % filter_type)
        out.append(bytes(line))
        previous = line
    return out


def _samples(rows, width, channels, bit_depth):
    """把反滤波后的每行展开成 8 bit 取样序列。

    row 已按 `(width*channels*bit_depth + 7) // 8` 字节对齐；按位深从**每个字节的
    高位**开始取，一行的取样总数是 width*channels，不是行字节数。
    """
    out = bytearray()
    for row in rows:
        if bit_depth == 8:
            out += row[:width * channels]
        elif bit_depth == 16:
            # 高字节即 8 bit 取样（java ImageIO 的 getRGB 也是 8 bit 每通道）
            out += row[0:width * channels * 2:2]
        elif bit_depth in (1, 2, 4):
            # 这里产出的是**原始取样值**（调色板索引 / 灰度级），不做位深归一化：
            # 调色板索引必须原样去查 PLTE，乘 17 会直接越界。
            # 灰度（color type 0/4）的位深归一化在下面的分支里按 maxval 处理。
            per_byte = 8 // bit_depth
            mask = (1 << bit_depth) - 1
            for i in range(width * channels):
                byte = row[i // per_byte]
                shift = 8 - bit_depth * ((i % per_byte) + 1)
                out.append((byte >> shift) & mask)
        else:
            raise ValueError("不支持的位深 %d" % bit_depth)
    return out


def read_png_argb(path):
    """返回 (width, height, [ARGB int, 按行优先])。"""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:8] != PNG_MAGIC:
        raise ValueError("不是 PNG 文件（魔数不匹配）")
    pos = 8
    idat = []
    palette = None
    trns = None
    width = height = bit_depth = color_type = None
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            (width, height, bit_depth, color_type, _comp, _filt, interlace
             ) = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
        if len(palette or ()) > 512:
            # 调色板最大 256 项 = 768 字节；超过说明这个 chunk 长度是错的，
            # 继续解码只会产出一个看起来合法的错指纹 —— 宁可明确失败。
            raise ValueError("PLTE 异常：%d 个调色板项" % len(palette))
    if width is None:
        raise ValueError("PNG 缺 IHDR")
    if color_type == 3 and len(palette or ()) > 256:
        raise ValueError("调色板 PNG 的 PLTE 超过 256 项")
    if interlace != 0:
        raise ValueError("不支持隔行 PNG（Adam7）")
    if color_type not in CHANNELS:
        raise ValueError("不支持的颜色类型 %d" % color_type)

    rows = _unfilter(zlib.decompress(b"".join(idat)), width, height,
                     CHANNELS[color_type], bit_depth)
    values = _samples(rows, width, CHANNELS[color_type], bit_depth)

    argb = []
    if color_type == 6:
        for i in range(0, len(values), 4):
            r, g, b, a = values[i:i + 4]
            argb.append((a << 24) | (r << 16) | (g << 8) | b)
    elif color_type == 2:
        for i in range(0, len(values), 3):
            r, g, b = values[i:i + 3]
            argb.append(0xFF000000 | (r << 16) | (g << 8) | b)
    elif color_type in (0, 4):
        # 灰度：子字节位深要按比例映射回 0..255（ImageIO 的 getRGB 也是 8 bit）
        scale = 255 // ((1 << bit_depth) - 1) if bit_depth < 8 else 1
        if color_type == 0:
            for value in values:
                g = value * scale
                argb.append(0xFF000000 | (g << 16) | (g << 8) | g)
        else:
            for i in range(0, len(values), 2):
                g, a = values[i] * scale, values[i + 1] * scale
                argb.append((a << 24) | (g << 16) | (g << 8) | g)
    else:
        if palette is None:
            raise ValueError("调色板 PNG 缺 PLTE")
        for index in values:
            if index >= len(palette):
                raise ValueError("调色板索引 %d 越界" % index)
            r, g, b = palette[index]
            a = trns[index] if (trns is not None and index < len(trns)) else 255
            argb.append((a << 24) | (r << 16) | (g << 8) | b)
    return width, height, argb


def fingerprint_png(path):
    """返回 "<宽>x<高>:<sha256hex>"，与 PngFingerprint.java 逐字节同构。"""
    width, height, argb = read_png_argb(path)
    digest = hashlib.sha256()
    digest.update(struct.pack(">II", width, height))
    # 必须严格 width*height 个 ARGB 取样：少一个都会让「解码器写错」变成
    # 一个看起来正常的十六进制指纹，而门禁只会说「被改动」。
    expected = width * height
    if len(argb) != expected:
        raise ValueError("像素取样数 %d 与 %dx%d 不符" % (len(argb), width, height))
    digest.update(struct.pack(">%dI" % expected, *argb))
    return "%dx%d:%s" % (width, height, digest.hexdigest())


def main(argv):
    if len(argv) < 2:
        print("用法: python3 scripts/asset_fingerprint.py <a.png> [b.png ...]",
              file=sys.stderr)
        return 2
    status = 0
    for path in argv[1:]:
        try:
            value = fingerprint_png(path)
        except Exception as exc:  # noqa: BLE001 - 命令行工具要报告而不是抛栈
            print("指纹失败 %s: %s" % (path, exc), file=sys.stderr)
            status = 1
            continue
        print("%s %s" % (value, path))
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
