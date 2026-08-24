# 生成 VectorDrawable 前景 + 校验 SVG（等价渲染，与 v24 对比像素差）
BLUE = "#4089FD"

# 原始坐标系下的路径（viewport 1024），组变换 translate(230.4 234.9) scale(0.55) 等价 v24
def rrect_path(x, y, w, h, r):
    x2, y2 = x + w, y + h
    return (f"M{x+r},{y} H{x2-r} A{r},{r} 0 0 1 {x2},{y+r} "
            f"V{y2-r} A{r},{r} 0 0 1 {x2-r},{y2} H{x+r} "
            f"A{r},{r} 0 0 1 {x},{y2-r} V{y+r} A{r},{r} 0 0 1 {x+r},{y} Z")

def circle_path(cx, cy, r):
    return f"M{cx-r},{cy} a{r},{r} 0 1 0 {2*r},0 a{r},{r} 0 1 0 {-2*r},0 Z"

tv_rect = rrect_path(132,181,760,500,78)
line1 = "M326,371 H458"
line2 = "M392,371 V539"
mv    = "M514,371 L578,539 L642,371"
botline = "M388,793 H636"
phone_rect = rrect_path(700,529,170,304,34)
c1 = circle_path(785,795,10)
c2 = circle_path(784,564,7)

# VectorDrawable XML
vd = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
    <group
        android:pivotX="512"
        android:pivotY="512"
        android:scaleX="0.55"
        android:scaleY="0.55"
        android:translateX="0"
        android:translateY="4.5">

        <!-- TV 外框 -->
        <path android:pathData="{tv_rect}" android:strokeColor="#FFFFFF" android:strokeWidth="40" android:strokeLineJoin="round"/>
        <!-- T -->
        <path android:pathData="{line1}" android:strokeColor="#FFFFFF" android:strokeWidth="48" android:strokeLineCap="round"/>
        <path android:pathData="{line2}" android:strokeColor="#FFFFFF" android:strokeWidth="48" android:strokeLineCap="round"/>
        <!-- V -->
        <path android:pathData="{mv}" android:strokeColor="#FFFFFF" android:strokeWidth="48" android:strokeLineCap="round" android:strokeLineJoin="round"/>
        <!-- 底部横线 -->
        <path android:pathData="{botline}" android:strokeColor="#FFFFFF" android:strokeWidth="36" android:strokeLineCap="round"/>

        <!-- 手机屏幕蓝色填充（遮挡背后的电视边框） -->
        <path android:pathData="{phone_rect}" android:fillColor="{BLUE}"/>
        <!-- 手机白色描边 -->
        <path android:pathData="{phone_rect}" android:strokeColor="#FFFFFF" android:strokeWidth="30" android:strokeLineJoin="round"/>
        <!-- 听筒 / 摄像头点 -->
        <path android:pathData="{c1}" android:fillColor="#FFFFFF"/>
        <path android:pathData="{c2}" android:fillColor="#FFFFFF"/>
    </group>
</vector>'''
open('ic_tv_cast_foreground.xml','w').write(vd)

# 等价校验 SVG（背景蓝 + 相同路径 + 相同变换）
tf = 'translate(230.4 234.9) scale(0.55)'
svg = f'''<svg width="1024" height="1024" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
<rect width="1024" height="1024" fill="{BLUE}"/>
<g transform="{tf}">
<path d="{tv_rect}" fill="none" stroke="#FFFFFF" stroke-width="40" stroke-linejoin="round"/>
<path d="{line1}" stroke="#FFFFFF" stroke-width="48" stroke-linecap="round"/>
<path d="{line2}" stroke="#FFFFFF" stroke-width="48" stroke-linecap="round"/>
<path d="{mv}" fill="none" stroke="#FFFFFF" stroke-width="48" stroke-linecap="round" stroke-linejoin="round"/>
<path d="{botline}" stroke="#FFFFFF" stroke-width="36" stroke-linecap="round"/>
<path d="{phone_rect}" fill="{BLUE}"/>
<path d="{phone_rect}" fill="none" stroke="#FFFFFF" stroke-width="30" stroke-linejoin="round"/>
<path d="{c1}" fill="#FFFFFF"/>
<path d="{c2}" fill="#FFFFFF"/>
</g>
</svg>'''
open('_vector_check.svg','w').write(svg)

import cairosvg
from PIL import Image, ImageChops
import io
# 渲染校验图（注意 v24 有圆角 rx220，这里背景是方角，只比中间图形区域）
cairosvg.svg2png(url='_vector_check.svg', write_to='_vector_check.png', output_width=1024, output_height=1024)
a = Image.open('_vector_check.png').convert('RGB')
b = Image.open('icon_tv_cast_v24_preview.png').convert('RGB')
# 只比较中心区域（避开 v24 圆角）
box = (150,150,874,874)
diff = ImageChops.difference(a.crop(box), b.crop(box))
bbox = diff.getbbox()
import numpy as np
arr = np.asarray(diff)
print("max diff:", arr.max(), "mean diff:", arr.mean())
print("done")
