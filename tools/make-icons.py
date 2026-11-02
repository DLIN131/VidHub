# 產生所有圖示：橘色圓角方塊＋白色播放鍵
# 用法：py -3 tools/make-icons.py（在專案根目錄執行）
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
TOP = (0xF5, 0x9E, 0x0B)
BOTTOM = (0xEA, 0x58, 0x0C)
SS = 4


def gradient(w, h):
    img = Image.new('RGBA', (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)) + (255,)
        for x in range(w):
            px[x, y] = c
    return img


def icon(size, radius=0.22, pad=0.0):
    big = size * SS
    g = gradient(big, big)
    mask = Image.new('L', (big, big), 0)
    p = int(big * pad)
    ImageDraw.Draw(mask).rounded_rectangle([p, p, big - p - 1, big - p - 1], radius=int((big - 2 * p) * radius), fill=255)
    img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    img.paste(g, (0, 0), mask)
    d = ImageDraw.Draw(img)
    s = big - 2 * p
    tri = [(p + s * 0.38, p + s * 0.28), (p + s * 0.38, p + s * 0.72), (p + s * 0.75, p + s * 0.50)]
    d.polygon(tri, fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


def banner(w, h):
    big_w, big_h = w * SS, h * SS
    img = gradient(big_w, big_h)
    d = ImageDraw.Draw(img)
    s = big_h * 0.5
    x0, y0 = big_w * 0.12, (big_h - s) / 2
    d.polygon([(x0, y0), (x0, y0 + s), (x0 + s * 0.9, y0 + s / 2)], fill=(255, 255, 255, 255))
    try:
        font = ImageFont.truetype('arialbd.ttf', int(big_h * 0.28))
    except OSError:
        font = ImageFont.load_default()
    d.text((big_w * 0.36, big_h / 2), 'VidHub', font=font, fill=(255, 255, 255, 255), anchor='lm')
    return img.resize((w, h), Image.LANCZOS)


def main():
    desk = ROOT / 'desktop'
    icon(512).save(desk / 'icon.png')
    icon(256).save(desk / 'icon.ico', sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    densities = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for module in ('phone', 'tv'):
        res = ROOT / 'android' / module / 'src' / 'main' / 'res'
        for name, px in densities.items():
            out = res / f'mipmap-{name}'
            out.mkdir(parents=True, exist_ok=True)
            icon(px, pad=0.04).save(out / 'ic_launcher.png')
    tv_res = ROOT / 'android' / 'tv' / 'src' / 'main' / 'res' / 'drawable-xhdpi'
    tv_res.mkdir(parents=True, exist_ok=True)
    banner(320, 180).save(tv_res / 'banner.png')
    print('ok')


if __name__ == '__main__':
    main()
