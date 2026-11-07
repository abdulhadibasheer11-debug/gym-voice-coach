import json, os, urllib.parse, urllib.request
os.makedirs("probe/out/img", exist_ok=True)
H = {"Accept": "application/json", "User-Agent": "gym-voice-coach-build"}
def get(u): return urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=25)
def en_name(r):
    for t in r.get("translations", []):
        if t.get("language") == 2: return t.get("name", "")
    return ""
TERMS = json.load(open("probe/terms.json"))
index = {}
for ex_id, terms in TERMS.items():
    seen, cands = set(), []
    for term in terms:
        try: data = json.load(get("https://wger.de/api/v2/exerciseinfo/?limit=60&name__search=" + urllib.parse.quote(term)))
        except Exception as e: print(ex_id, term, e); continue
        rs = [r for r in data.get("results", []) if r.get("images") and en_name(r)]
        rs.sort(key=lambda r: (en_name(r).lower() != term.lower(), len(en_name(r))))
        for r in rs:
            if r["id"] in seen: continue
            seen.add(r["id"])
            img = sorted(r["images"], key=lambda i: not i.get("is_main"))[0]
            url = (img.get("thumbnails") or {}).get("small") or img.get("image")
            if not url.lower().endswith((".jpg", ".jpeg")): continue
            k = len(cands)
            try: open(f"probe/out/img/{ex_id}_{k}.jpg", "wb").write(get(url).read())
            except Exception as e: continue
            cands.append({"k": k, "wger": r["id"], "name": en_name(r), "author": img.get("license_author") or r.get("license_author") or "",
                          "medium": (img.get("thumbnails") or {}).get("medium") or img.get("image")})
            if len(cands) >= 6: break
        if len(cands) >= 6: break
    index[ex_id] = cands
    print(ex_id, [c["name"] for c in cands])
json.dump(index, open("probe/out/images.json", "w"), indent=1)
titles = ["Volatile Reaction","Movement Proposition","Pump","Raving Energy","Danger Storm","Take a Chance","Funkorama","Ouroboros",
          "Fearless First","Rhinoceros","Hard Boiled","Cipher","Exciting Trailer","Rocket","Overdrive","Club Diver","Electrodoodle",
          "Itty Bitty 8 Bit","Power Restored","Unrelenting","Blip Stream","Killers","Five Armies","Darkest Child","Sneaky Adventure",
          "Aggressor","Ghost Dance","Constance","Crypto","Hypnothis","Intrepid","Vicious","Crusade","Corruption","Big Rock",
          "Brittle Rille","Space Fighter Loop","Clash Defiant","Cut and Run","Dub Hub","Nerves","Rollin at 5","Hit the Streets"]
music = []
for t in titles:
    u = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/" + urllib.parse.quote(t) + ".mp3"
    try:
        r = urllib.request.urlopen(urllib.request.Request(u, method="HEAD", headers={"User-Agent": "Mozilla/5.0"}), timeout=20)
        music.append({"title": t, "url": u, "size": int(r.headers.get("Content-Length", 0))}); print("MUSIC OK", t, r.headers.get("Content-Length"))
    except Exception as e: print("MUSIC NO", t, e)
json.dump(music, open("probe/out/music.json", "w"), indent=1)
