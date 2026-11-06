"""Build step: download one illustration per exercise from wger.de (CC-BY-SA) into the app's assets."""
import json, os, sys, urllib.parse, urllib.request

TERMS = {
    "chest_press_high": ["chest press"], "smith_bench": ["smith machine bench press", "bench press"],
    "incline_machine": ["incline chest press", "incline press"], "seated_dip": ["dip machine", "dips", "dip"],
    "pec_deck": ["pec deck", "butterfly"], "tri_rope": ["triceps pushdown rope", "rope pushdown", "triceps pushdown"],
    "tri_bar": ["triceps pushdown", "pushdown"], "tri_db_overhead": ["overhead triceps extension", "triceps extension", "french press"],
    "tri_plate": ["overhead triceps extension", "triceps extension", "french press"],
    "pulldown_wide": ["wide grip lat pulldown", "lat pulldown"], "cable_row": ["seated cable row", "cable row"],
    "pulldown_neutral": ["close grip lat pulldown", "lat pulldown"], "machine_row": ["seated row machine", "seated row"],
    "tbar_row": ["t-bar row", "t bar row"], "back_ext": ["hyperextension", "hyperextensions", "back extension"],
    "biceps_machine": ["machine bicep curl", "biceps curl machine", "preacher curl"], "barbell_curl": ["barbell curl", "biceps curl barbell"],
    "cable_curl_rope": ["cable hammer curl", "rope hammer curl", "hammer curl"], "cable_curl_bar": ["cable curl", "cable biceps curl"],
    "wrist_curl": ["wrist curl", "wrist curls"], "db_lateral": ["lateral raise", "lateral raises"],
    "db_press": ["dumbbell shoulder press", "shoulder press dumbbells"], "machine_shoulder_press": ["shoulder press machine", "machine shoulder press", "shoulder press"],
    "machine_lateral": ["machine lateral raise", "lateral raise"], "reverse_pec_deck": ["reverse pec deck", "reverse fly", "reverse butterfly"],
    "face_pull": ["face pull"], "db_shrug": ["dumbbell shrug", "shrugs dumbbells", "shrug"], "leg_ext": ["leg extension"],
    "leg_curl": ["lying leg curl", "leg curl"], "leg_press": ["leg press"], "squat_machine": ["hack squat", "machine squat"],
    "adductor": ["hip adduction", "adductor machine", "adduction"], "abductor": ["hip abduction", "abduction"],
    "calf_seated": ["seated calf raise", "calf raise"], "abs_machine": ["crunch machine", "ab crunch", "crunch"],
    "knee_raise": ["captain's chair knee raise", "knee raise", "leg raise"], "cable_crunch": ["cable crunch", "kneeling cable crunch"],
    "decline_crunch": ["decline crunch", "decline sit-up", "sit-up", "sit up"], "torso_rotation": ["rotary torso", "torso twist", "russian twist"],
    "barbell_bench": ["barbell bench press", "bench press"], "incline_db_press": ["incline dumbbell press", "incline press dumbbell", "incline bench press"],
    "db_fly": ["dumbbell fly", "dumbbell flyes", "flyes"], "cable_crossover": ["cable crossover", "cable cross-over", "cable fly"],
    "push_up": ["push-up", "push up"], "pull_up": ["pull-up", "pull-ups", "pull up"],
    "db_row": ["one arm dumbbell row", "bent over dumbbell row", "dumbbell row"], "deadlift": ["deadlift", "deadlifts"],
    "barbell_squat": ["barbell squat", "barbell full squat"], "lunge": ["lunges", "lunge"], "rdl": ["romanian deadlift"],
    "hip_thrust": ["hip thrust"], "standing_calf": ["standing calf raise"], "db_curl": ["dumbbell curl", "biceps curl dumbbell"],
    "hammer_curl": ["hammer curl", "hammer curls"], "skull_crusher": ["skull crusher", "skullcrusher", "lying triceps extension"],
    "front_raise": ["front raise", "front raises"], "upright_row": ["upright row"],
}
EXCLUDE = {"pulldown_wide": ["close", "supinated", "reverse"], "barbell_curl": ["reverse"], "db_row": ["upright"],
           "db_curl": ["press"], "smith_bench": ["squat"], "chest_press_high": ["incline", "decline"], "face_pull": ["dumbbell", "bent"]}

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
            data = json.load(get("https://wger.de/api/v2/exerciseinfo/?limit=60&name__search=" + urllib.parse.quote(term)))
        except Exception as e:
            print(f"{ex_id}: search '{term}' failed: {e}"); continue
        norm = lambda x: x.lower().replace("-", " ").replace("'", "")
        words = norm(term).split()
        bad = EXCLUDE.get(ex_id, [])
        results = [r for r in data.get("results", []) if r.get("images")
                   and all(w in norm(en_name(r)) for w in words) and not any(b in norm(en_name(r)) for b in bad)]
        results.sort(key=lambda r: (norm(en_name(r)) != norm(term), len(en_name(r))))
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
