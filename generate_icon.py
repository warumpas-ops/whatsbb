import os, math
from PIL import Image, ImageDraw

def draw_minimalist_icon(size):
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    pad = int(S * 0.05)
    # Pure pitch black circle or rounded squircle background
    draw.ellipse([pad, pad, S - pad, S - pad], fill=(12, 12, 12, 255))
    
    # Subtle 1px inner rim
    draw.ellipse([pad, pad, S - pad, S - pad], outline=(38, 38, 38, 255), width=int(S * 0.015))

    # White minimal speech bubble contour
    bcx, bcy = S * 0.50, S * 0.49
    br = S * 0.28
    
    # White filled bubble
    draw.ellipse([bcx - br, bcy - br, bcx + br, bcy + br], fill=(245, 245, 245, 255))
    
    # Minimalist bubble tail pointing down-left
    tail_pts = [
        (bcx - br * 0.85, bcy + br * 0.25),
        (bcx - br * 0.30, bcy + br * 0.85),
        (bcx - br * 1.05, bcy + br * 0.95),
    ]
    draw.polygon(tail_pts, fill=(245, 245, 245, 255))
    draw.ellipse([bcx - br * 1.15, bcy + br * 0.80, bcx - br * 0.95, bcy + br * 1.05], fill=(245, 245, 245, 255))

    # Black minimal phone handset inside white bubble
    phone_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    pdraw = ImageDraw.Draw(phone_layer)
    
    hr = br * 0.55
    bbox = [bcx - hr, bcy - hr, bcx + hr, bcy + hr]
    pdraw.arc(bbox, start=135, end=275, fill=(12, 12, 12, 255), width=int(br * 0.32))
    
    ex = bcx + hr * math.cos(math.radians(275))
    ey = bcy + hr * math.sin(math.radians(275))
    ew = br * 0.26
    pdraw.ellipse([ex - ew, ey - ew, ex + ew, ey + ew], fill=(12, 12, 12, 255))
    
    mx = bcx + hr * math.cos(math.radians(135))
    my = bcy + hr * math.sin(math.radians(135))
    pdraw.ellipse([mx - ew, my - ew, mx + ew, my + ew], fill=(12, 12, 12, 255))

    img = Image.alpha_composite(img, phone_layer)
    return img.resize((size, size), Image.Resampling.LANCZOS)

import os
res_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res")
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
    icon = draw_minimalist_icon(sz)
    icon.save(out_path, "PNG")
    print(f"Saved minimalist monochrome {sz}x{sz} icon to {out_path}")
