from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"
ASSETS.mkdir(exist_ok=True)

size = 1024
image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)

# Rounded blue tile with a simple white WiFi glyph.
draw.rounded_rectangle((52, 52, 972, 972), radius=220, fill="#1565C0")
center = size // 2
for inset, width in ((210, 62), (305, 62), (400, 62)):
    box = (inset, inset + 80, size - inset, size - inset + 80)
    draw.arc(box, start=205, end=335, fill="white", width=width)
draw.ellipse((center - 54, 690, center + 54, 798), fill="white")

preview = ASSETS / "icon-preview.png"
image.save(preview)
image.save(
    ASSETS / "atcn-wifi.ico",
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)
image.save(ASSETS / "atcn-wifi.icns", format="ICNS")
print(f"icons={ASSETS}")

