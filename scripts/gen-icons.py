# Generates app/src/main/java/org/anikodev/candywrapper/ui/Icons.kt from CandyWrapper's IconData.generated.swift.
#
#   python3 scripts/gen-icons.py ~/CandyWrapper/Packages/CandyKit/Sources/DesignSystem/Icons/IconData.generated.swift \
#     scripts/Icons.kt.tpl "$(cat scripts/icon-names.txt)" > app/src/main/java/org/anikodev/candywrapper/ui/Icons.kt
#
# icon-names.txt is the comma-separated list of icons the app uses; add a name there and run again.
import re, sys
src = open(sys.argv[1]).read()
names = sys.argv[3].split(',')
out = []
def fmt(v): return ('%g' % float(v))
for name in names:
    m = re.search(r'case \.%s:\n\s*return IconDefinition\(viewBox: IconViewBox\(x: ([-\d.]+), y: ([-\d.]+), width: ([\d.]+), height: ([\d.]+)\), elements: \[\n(.*?)\n\s*\]\)' % name, src, re.S)
    if not m: raise SystemExit('missing ' + name)
    vx, vy, vw, vh, body = m.groups()
    els = []
    for line in body.split('\n'):
        line = line.strip()
        if not line: continue
        shape = None
        if (p := re.match(r'IconElement\(\.path\(d: "([^"]*)"\)(.*)', line)): d, rest = p.groups()
        elif (p := re.match(r'IconElement\(\.circle\(cx: ([-\d.]+), cy: ([-\d.]+), r: ([-\d.]+)\)\)(.*)', line)):
            cx, cy, r, rest = p.groups(); cx, cy, r = float(cx), float(cy), float(r)
            d = f"M{fmt(cx-r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2*r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2*r)} 0"
        elif (p := re.match(r'IconElement\(\.ellipse\(cx: ([-\d.]+), cy: ([-\d.]+), rx: ([-\d.]+), ry: ([-\d.]+)\)\)(.*)', line)):
            cx, cy, rx, ry, rest = p.groups(); cx, cy, rx, ry = map(float, (cx, cy, rx, ry))
            d = f"M{fmt(cx-rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2*rx)} 0a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2*rx)} 0"
        elif (p := re.match(r'IconElement\(\.rect\(x: ([-\d.]+), y: ([-\d.]+), w: ([-\d.]+), h: ([-\d.]+), rx: ([-\d.]+), ry: ([-\d.]+)\)\)(.*)', line)):
            x, y, w, h, rx, ry, rest = p.groups(); x, y, w, h, r = map(float, (x, y, w, h, rx))
            if r > 0:
                d = f"M{fmt(x+r)} {fmt(y)}h{fmt(w-2*r)}a{fmt(r)} {fmt(r)} 0 0 1 {fmt(r)} {fmt(r)}v{fmt(h-2*r)}a{fmt(r)} {fmt(r)} 0 0 1 {fmt(-r)} {fmt(r)}h{fmt(-(w-2*r))}a{fmt(r)} {fmt(r)} 0 0 1 {fmt(-r)} {fmt(-r)}v{fmt(-(h-2*r))}a{fmt(r)} {fmt(r)} 0 0 1 {fmt(r)} {fmt(-r)}z"
            else: d = f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
        elif (p := re.match(r'IconElement\(\.line\(x1: ([-\d.]+), y1: ([-\d.]+), x2: ([-\d.]+), y2: ([-\d.]+)\)\)(.*)', line)):
            x1, y1, x2, y2, rest = p.groups(); d = f"M{x1} {y1}L{x2} {y2}"
        elif (p := re.match(r'IconElement\(\.polyline\(points: "([^"]*)"\)\)(.*)', line)):
            pts, rest = p.groups(); nums = re.findall(r'[-\d.]+', pts)
            d = 'M' + ' L'.join(f"{nums[i]} {nums[i+1]}" for i in range(0, len(nums), 2))
        else: raise SystemExit('unparsed ' + name + ': ' + line[:80])
        fill = None
        if 'fill: .current' in rest: fill = 'cur'
        elif (c := re.search(r'fill: \.rgba\(0x([0-9A-F]{8})\)', rest)): fill = '0x' + c.group(1)[6:] + c.group(1)[:6]
        els.append((d, fill))
    kname = name[0].upper() + name[1:]
    if all(f is None for _, f in els):
        out.append(f'    val {kname} by stroke("{name}", ' + ', '.join(f'"{d}"' for d, _ in els) + ')')
    else:
        parts = ', '.join(f'"{d}" to {("null" if f in (None,"cur") else "Color(" + f + ")")}' for d, f in els)
        out.append(f'    val {kname} by filled("{name}", {fmt(vx)}f, {fmt(vy)}f, {fmt(vw)}f, {fmt(vh)}f, {parts})')
tpl = open(sys.argv[2]).read()
print(tpl.replace('//ICONS', '\n'.join(out)))
