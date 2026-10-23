import os, math
from PIL import Image, ImageDraw

def draw_whatsapp_icon(size):
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    pad = int(S * 0.04)
    # 1. Vibrant WhatsApp Green circle with subtle gradient
    cx, cy, radius = S / 2, S / 2, (S / 2) - pad
    for y in range(int(pad), int(S - pad)):
        t = (y - pad) / (S - 2 * pad)
        r = int(37 * (1 - t) + 18 * t)
        g = int(211 * (1 - t) + 195 * t)
        b = int(102 * (1 - t) + 85 * t)
        dy = y - cy
        if abs(dy) < radius:
            dx = math.sqrt(radius * radius - dy * dy)
            draw.line([(cx - dx, y), (cx + dx, y)], fill=(r, g, b, 255))

    # 2. White speech bubble
    bcx, bcy = S * 0.50, S * 0.49
    br = S * 0.30
    draw.ellipse([bcx - br, bcy - br, bcx + br, bcy + br], fill=(255, 255, 255, 255))
    
    # Bubble tail pointing to bottom-left
    tail_pts = [
        (bcx - br * 0.85, bcy + br * 0.25),
        (bcx - br * 0.30, bcy + br * 0.85),
        (bcx - br * 1.05, bcy + br * 0.95),
    ]
    draw.polygon(tail_pts, fill=(255, 255, 255, 255))
    draw.ellipse([bcx - br * 1.15, bcy + br * 0.80, bcx - br * 0.95, bcy + br * 1.05], fill=(255, 255, 255, 255))

    # 3. Green Phone Handset
    # Accurate phone handset rotated ~45 deg
    phone_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    pdraw = ImageDraw.Draw(phone_layer)
    
    hr = br * 0.56
    bbox = [bcx - hr, bcy - hr, bcx + hr, bcy + hr]
    pdraw.arc(bbox, start=135, end=275, fill=(37, 211, 102, 255), width=int(br * 0.32))
    
    ex = bcx + hr * math.cos(math.radians(275))
    ey = bcy + hr * math.sin(math.radians(275))
    ew = br * 0.26
    pdraw.ellipse([ex - ew, ey - ew, ex + ew, ey + ew], fill=(37, 211, 102, 255))
    
    mx = bcx + hr * math.cos(math.radians(135))
    my = bcy + hr * math.sin(math.radians(135))
    pdraw.ellipse([mx - ew, my - ew, mx + ew, my + ew], fill=(37, 211, 102, 255))

    img = Image.alpha_composite(img, phone_layer)
    return img.resize((size, size), Image.Resampling.LANCZOS)

res_dir = r"C:\Users\Growth\.gemini\antigravity\scratch\whatsbb\app\src\main\res"
sizes = {
    "drawable": 96,
    "drawable-mdpi": 48,
    "drawable-hdpi": 72,
    "drawable-xhdpi": 96,
    "drawable-xxhdpi": 144,
    "drawable-xxxhdpi": 192,
}

for folder, sz in sizes.items():
    out_path = os.path.join(res_dir, folder, "ic_launcher.png")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    icon = draw_whatsapp_icon(sz)
    icon.save(out_path, "PNG")
    print(f"Saved {sz}x{sz} icon to {out_path}")
