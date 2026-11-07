import json, os, re, urllib.request
os.makedirs("probe/out2", exist_ok=True)
base = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/"
db = json.load(urllib.request.urlopen(base + "dist/exercises.json", timeout=60))
print("exercises:", len(db))
WANT = {
 "chest_press_high": ["machine bench press", "leverage chest press", "chest press"], "smith_bench": ["smith machine bench press"],
 "incline_machine": ["leverage incline chest press", "incline chest press", "machine incline"], "seated_dip": ["dip machine", "dips triceps", "parallel bar dip"],
 "pec_deck": ["butterfly", "pec deck"], "tri_rope": ["triceps pushdown rope"], "tri_bar": ["triceps pushdown v-bar", "triceps pushdown"],
 "tri_db_overhead": ["standing dumbbell triceps extension", "seated triceps press", "dumbbell triceps extension"], "tri_plate": ["standing dumbbell triceps extension", "seated triceps press"],
 "pulldown_wide": ["wide-grip lat pulldown"], "cable_row": ["seated cable rows"], "pulldown_neutral": ["close-grip front lat pulldown", "v-bar pulldown"],
 "machine_row": ["leverage high row", "seated row machine", "leverage iso row"], "tbar_row": ["lying t-bar row", "t-bar row"],
 "back_ext": ["hyperextensions back extensions", "back extension"], "biceps_machine": ["machine bicep curl", "machine preacher curls"],
 "barbell_curl": ["barbell curl"], "cable_curl_rope": ["cable hammer curls rope"], "cable_curl_bar": ["standing biceps cable curl", "cable curl"],
 "wrist_curl": ["palms-up barbell wrist curl", "seated dumbbell palms-up wrist curl", "wrist curl"], "db_lateral": ["side lateral raise"],
 "db_press": ["dumbbell shoulder press", "seated dumbbell press"], "machine_shoulder_press": ["machine shoulder military press", "leverage shoulder press"],
 "machine_lateral": ["lateral raise machine", "machine lateral raise"], "reverse_pec_deck": ["reverse machine flyes"], "face_pull": ["face pull"],
 "db_shrug": ["dumbbell shrug"], "leg_ext": ["leg extensions"], "leg_curl": ["lying leg curls"], "leg_press": ["leg press"], "squat_machine": ["hack squat"],
 "adductor": ["thigh adductor"], "abductor": ["thigh abductor"], "calf_seated": ["seated calf raise"], "abs_machine": ["ab crunch machine"],
 "knee_raise": ["knee hip raise on parallel bars", "hanging leg raise"], "cable_crunch": ["cable crunch"], "decline_crunch": ["decline crunch"],
 "torso_rotation": ["torso rotation", "rotary torso", "cable russian twists"], "barbell_bench": ["barbell bench press medium grip"],
 "incline_db_press": ["incline dumbbell press"], "db_fly": ["dumbbell flyes"], "cable_crossover": ["cable crossover"], "push_up": ["pushups"],
 "pull_up": ["pullups"], "db_row": ["one-arm dumbbell row"], "deadlift": ["barbell deadlift"], "barbell_squat": ["barbell full squat", "barbell squat"],
 "lunge": ["dumbbell lunges"], "rdl": ["romanian deadlift"], "hip_thrust": ["barbell hip thrust", "barbell glute bridge"], "standing_calf": ["standing calf raises"],
 "db_curl": ["dumbbell bicep curl"], "hammer_curl": ["hammer curls"], "skull_crusher": ["ez-bar skullcrusher", "lying triceps press"],
 "front_raise": ["front dumbbell raise"], "upright_row": ["upright barbell row"],
}
norm = lambda s: re.sub(r"[^a-z0-9 ]", " ", s.lower())
def score(name, q):
    n, qs = set(norm(name).split()), norm(q).split()
    return sum(w in n for w in qs) / len(qs) - 0.02 * len(n)
out = {}
for ex_id, qs in WANT.items():
    ranked = []
    for e in db:
        s = max(score(e["name"], q) for q in qs)
        if s > 0.6 and e.get("images"): ranked.append((s, e))
    ranked.sort(key=lambda t: -t[0])
    cands = []
    for k, (s, e) in enumerate(ranked[:4]):
        try:
            data = urllib.request.urlopen(base + "exercises/" + e["images"][0], timeout=30).read()
            open(f"probe/out2/{ex_id}_{k}.jpg", "wb").write(data)
            cands.append({"k": k, "name": e["name"], "path": e["images"][0], "path2": e["images"][1] if len(e["images"]) > 1 else None})
        except Exception as x: print(ex_id, x)
    out[ex_id] = cands
    print(ex_id, [c["name"] for c in cands])
json.dump(out, open("probe/out2/fedb.json", "w"), indent=1)
