# 生成 TV launcher banner (320x180dp @xhdpi = 640x360px)
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 360
img = Image.new("RGB", (W, H), "#2f6fdb")
d = ImageDraw.Draw(img)
# 底部渐变感:半透明深色圆角内框
d.rounded_rectangle([16, 16, W - 16, H - 16], radius=24, outline="#7fb4ff", width=4)

# 房子图标(与 ic_launcher 同形)
lw = 10
d.polygon([(170, 95), (240, 40), (310, 95)], outline="white", width=lw)
d.line([(190, 82), (190, 210)], fill="white", width=lw)
d.line([(290, 82), (290, 210)], fill="white", width=lw)
d.line([(190, 210), (290, 210)], fill="white", width=lw)
for cx, cy in [(240, 140), (215, 180), (265, 180)]:
    d.ellipse([cx - 12, cy - 12, cx + 12, cy + 12], fill="white")
d.line([(240, 150), (220, 175)], fill="white", width=6)
d.line([(240, 150), (260, 175)], fill="white", width=6)
d.line([(222, 183), (258, 183)], fill="white", width=6)

font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 92)
d.text((350, 120), "网络邻居", font=font, fill="white")
font2 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 40)
d.text((352, 230), "SMB 局域网共享浏览", font=font2, fill="#bcd6ff")

img.save("app/src/main/res/drawable-xhdpi/banner.png")
print("banner.png", img.size)
