#!/opt/homebrew/bin/python3
"""Генерирует квадратное тестовое изображение из случайных серых кругов.

    python3 scripts/generate_image.py [сторона] -o имя.jpg

Круги, а не однотонная заливка, потому что по ним на любом зуме видно, подгрузился
тайл или нет.

Путь вывода считается от текущего каталога, поэтому запускать надо из корня проекта.
Готовый файл переносят в app/src/main/assets руками.
"""

from PIL import Image, ImageDraw
import argparse
import random
import os

parser = argparse.ArgumentParser(description="Generate a large square test image")
parser.add_argument("size", type=int, nargs="?", default=30000, help="Side length in pixels (default: 30000)")
parser.add_argument("-o", "--output", default="test_image_large.jpg", help="Output filename (default: test_image_large.jpg)")
args = parser.parse_args()

W = H = args.size
img = Image.new("RGB", (W, H), "white")
draw = ImageDraw.Draw(img)

# Радиус круга в пикселях исходника. Число кругов фиксировано, а сторона приходит
# аргументом, поэтому на большой картинке круги лежат реже.
circle_r=20
for _ in range(50000):
    x, y = random.randint(0, W), random.randint(0, H)
    draw.ellipse([x-circle_r, y-circle_r, x+circle_r, y+circle_r], fill=(random.randint(0,255),)*3)

res_dir= "scripts/generate_image_result"
os.makedirs(res_dir, exist_ok=True)
full_path=os.path.abspath(os.path.join(res_dir, args.output))
img.save(full_path, quality=85)

size_mb = os.path.getsize(full_path) / (1024 * 1024)

print(f"An image of size {W}x{H} px was successfully generated and saved to {full_path} ({size_mb:.1f} MB)")
