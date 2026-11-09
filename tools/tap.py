"""Print the centre "x y" of the first UI node whose text or content-desc equals (or starts with) argv[2]."""
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
want = sys.argv[2]
for n in root.iter('node'):
    for k in ('content-desc', 'text'):
        v = n.get(k) or ''
        if v == want or v.startswith(want):
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
