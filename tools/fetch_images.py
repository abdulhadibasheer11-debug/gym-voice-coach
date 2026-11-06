"""Build step: download one illustration per exercise from wger.de (CC-BY-SA) into the app's assets."""
import json, os, sys, urllib.parse, urllib.request

TERMS = {
    "chest_press_high": ["machine chest press", "chest press"], "smith_bench": ["smith machine bench press", "bench press"],
    "incline_machine": ["incline chest press", "incline bench press", "incline press"], "seated_dip": ["dip machine", "seated dip", "dips"],
    "pec_deck": ["pec deck", "butterfly", "fly machine"], "tri_rope": ["triceps pushdown rope", "rope pushdown", "triceps pushdown"],
    "tri_bar": ["triceps pushdown", "pushdown"], "tri_db_overhead": ["overhead triceps extension", "triceps extension"],
    "tri_plate": ["overhead triceps extension", "triceps extension"], "pulldown_wide": ["lat pulldown", "wide grip pulldown", "pulldown"],
    "cable_row": ["seated cable row", "cable row", "seated row"], "pulldown_neutral": ["close grip pulldown", "lat pulldown"],
    "machine_row": ["seated row machine", "machine row", "seated row"], "tbar_row": ["t-bar row", "t bar row", "bent over row"],
    "back_ext": ["hyperextension", "back extension"], "biceps_machine": ["biceps curl machine", "machine curl", "preacher curl"],
    "barbell_curl": ["barbell curl", "biceps curl"], "cable_curl_rope": ["cable hammer curl", "hammer curl"],
    "cable_curl_bar": ["cable curl", "biceps curl"], "wrist_curl": ["wrist curl"], "db_lateral": ["lateral raise", "dumbbell lateral raise"],
    "db_press": ["dumbbell shoulder press", "shoulder press"], "machine_shoulder_press": ["machine shoulder press", "shoulder press"],
    "machine_lateral": ["machine lateral raise", "lateral raise"], "reverse_pec_deck": ["reverse fly", "rear delt fly", "reverse butterfly"],
    "face_pull": ["face pull"], "db_shrug": ["dumbbell shrug", "shrug"], "leg_ext": ["leg extension"], "leg_curl": ["lying leg curl", "leg curl"],
    "leg_press": ["leg press"], "squat_machine": ["hack squat", "machine squat", "squat"], "adductor": ["adductor", "hip adduction"],
    "abductor": ["abductor", "hip abduction"], "calf_seated": ["seated calf raise", "calf raise"], "abs_machine": ["crunch machine", "crunch"],
    "knee_raise": ["knee raise", "hanging leg raise", "leg raise"], "cable_crunch": ["cable crunch", "crunch"],
    "decline_crunch": ["decline crunch", "sit-up", "crunch"], "torso_rotation": ["rotary torso", "russian twist", "twist"],
    "barbell_bench": ["bench press"], "incline_db_press": ["incline dumbbell press", "incline bench press"], "db_fly": ["dumbbell fly", "fly"],
    "cable_crossover": ["cable crossover", "cable fly"], "push_up": ["push-up", "push up", "pushups"], "pull_up": ["pull-up", "pull up", "chin-up"],
    "db_row": ["dumbbell row", "one arm row"], "deadlift": ["deadlift"], "barbell_squat": ["barbell squat", "squat"], "lunge": ["lunge"],
    "rdl": ["romanian deadlift", "stiff-legged deadlift"], "hip_thrust": ["hip thrust", "glute bridge"], "standing_calf": ["standing calf raise", "calf raise"],
    "db_curl": ["dumbbell curl", "biceps curl"], "hammer_curl": ["hammer curl"], "skull_crusher": ["skull crusher", "lying triceps extension"],
    "front_raise": ["front raise"], "upright_row": ["upright row"],
}

def get(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "gym-voice-coach-build"})
    return urllib.request.urlopen(req, timeout=25)

def en_name(r):
    for t in r.get("translations", []):
        if t.get("language") == 2: return t.get("name", "")
    return (r.get("translations") or [{}])[0].get("name", "")

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
credits, found = [], 0
for ex_id, terms in TERMS.items():
    done = False
    for term in terms:
        if done: break
        try:
            data = json.load(get("https://wger.de/api/v2/exerciseinfo/?limit=30&name__search=" + urllib.parse.quote(term)))
        except Exception as e:
            print(f"{ex_id}: search '{term}' failed: {e}"); continue
        results = [r for r in data.get("results", []) if r.get("images")]
        words = term.lower().split()
        results.sort(key=lambda r: (not all(w in en_name(r).lower() for w in words), len(en_name(r))))
        for r in results:
            imgs = sorted(r["images"], key=lambda i: not i.get("is_main"))
            img = imgs[0]
            url = (img.get("thumbnails") or {}).get("medium") or img.get("image")
            if not url or not url.lower().split("?")[0].endswith((".jpg", ".jpeg", ".png", ".webp.400x400_q85.jpg")):
                continue
            try:
                raw = get(url).read()
            except Exception as e:
                print(f"{ex_id}: download failed {e}"); continue
            if not url.lower().endswith((".jpg", ".jpeg")):
                continue  # keep only JPEG (decodable everywhere, small)
            open(os.path.join(out_dir, ex_id + ".jpg"), "wb").write(raw)
            credits.append({"id": ex_id, "name": en_name(r), "author": img.get("license_author") or r.get("license_author") or "",
                            "license": "CC-BY-SA", "source": f"https://wger.de/exercise/{r['id']}"})
            print(f"{ex_id}: '{term}' -> {en_name(r)} ({url})")
            found += 1; done = True
            break
    if not done: print(f"{ex_id}: NO IMAGE")
json.dump(credits, open(os.path.join(out_dir, "credits.json"), "w"), ensure_ascii=False, indent=1)
print(f"images: {found}/{len(TERMS)}")
