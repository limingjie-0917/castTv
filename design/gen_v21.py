import cairosvg
from PIL import Image
import io

SCALE = 0.55  # 从 0.60 缩小到 0.55，增大四周留白

def build_svg(scale, offset_y):
    return f'''<svg width="1024" height="1024" viewBox="0 0 1024 1024" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="1024" height="1024" rx="220" fill="#000000"/>

  <!-- v21: graphic scaled to {scale} for larger padding; vertical center via pixel scan -->
  <g transform="translate(512 512) scale({scale}) translate(-512 -512) translate(0 {offset_y})">
    <g id="tv-symbol">
      <rect x="132" y="181" width="760" height="500" rx="78" stroke="#FFFFFF" stroke-width="40"/>

      <path d="M326 371H458" stroke="#FFFFFF" stroke-width="48" stroke-linecap="round"/>
      <path d="M392 371V539" stroke="#FFFFFF" stroke-width="48" stroke-linecap="round"/>
      <path d="M514 371L578 539L642 371" stroke="#FFFFFF" stroke-width="48" stroke-linecap="round" stroke-linejoin="round"/>

      <path d="M388 793H636" stroke="#FFFFFF" stroke-width="36" stroke-linecap="round"/>
    </g>

    <rect x="700" y="529" width="170" height="304" rx="34" fill="#000000"/>
    <rect x="700" y="529" width="170" height="304" rx="34" stroke="#FFFFFF" stroke-width="30"/>
    <circle cx="785" cy="795" r="10" fill="#FFFFFF"/>
    <circle cx="784" cy="564" r="7" fill="#FFFFFF"/>
  </g>
</svg>'''

def render_png_bytes(svg):
    return cairosvg.svg2png(bytestring=svg.encode('utf-8'), output_width=1024, output_height=1024)

def white_bbox(png_bytes):
    img = Image.open(io.BytesIO(png_bytes)).convert('RGBA')
    px = img.load()
    w, h = img.size
    top, bottom = None, None
    for y in range(h):
        row_has = False
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > 20 and (r > 60 or g > 60 or b > 60):  # non-black white-ish
                row_has = True
                break
        if row_has:
            if top is None:
                top = y
            bottom = y
    return top, bottom, h

# 迭代求垂直居中偏移
offset = 0.0
for i in range(8):
    svg = build_svg(SCALE, offset)
    png = render_png_bytes(svg)
    top, bottom, h = white_bbox(png)
    center = (top + bottom) / 2.0
    desired = h / 2.0
    delta_px = desired - center           # PNG 像素差
    delta_content = delta_px / SCALE      # 换算回内容坐标（因为缩放）
    print(f"iter {i}: top={top} bottom={bottom} center={center:.1f} top_pad={top} bottom_pad={h-1-bottom} delta_px={delta_px:.1f} offset={offset:.2f}")
    if abs(delta_px) < 0.6:
        break
    offset += delta_content

# 最终产物
final_svg = build_svg(SCALE, round(offset, 3))
with open('icon_tv_cast_v21.svg', 'w') as f:
    f.write(final_svg)
png = render_png_bytes(final_svg)
with open('icon_tv_cast_v21_preview.png', 'wb') as f:
    f.write(png)
top, bottom, h = white_bbox(png)
print(f"FINAL offset={offset:.3f} top_pad={top} bottom_pad={h-1-bottom} height={bottom-top}")
