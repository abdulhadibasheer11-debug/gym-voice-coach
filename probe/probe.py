import json, urllib.request, urllib.parse
terms = ["lat pulldown","bench press","chest press","pec deck","butterfly","triceps pushdown","biceps curl","leg press","leg extension","leg curl","lateral raise","shoulder press","shrug","seated row","calf raise","crunch","hip adduction","face pull","hack squat","t-bar row","hyperextension","wrist curl"]
def get(url):
    req = urllib.request.Request(url, headers={"Accept":"application/json","User-Agent":"gym-voice-coach-probe"})
    return json.load(urllib.request.urlopen(req, timeout=20))
for t in terms[:3]:
    for url in [f"https://wger.de/api/v2/exercise/search/?term={urllib.parse.quote(t)}&language=en",
                f"https://wger.de/api/v2/exerciseinfo/?name__search={urllib.parse.quote(t)}&limit=3"]:
        try:
            d = get(url); s = json.dumps(d)[:1500]; print("URL", url, "\n", s, "\n")
        except Exception as e: print("ERR", url, e)
for t in terms:
    try:
        d = get(f"https://wger.de/api/v2/exercise/search/?term={urllib.parse.quote(t)}&language=en")
        sug = d.get("suggestions", [])
        print(t, "=>", [(x["data"].get("name"), x["data"].get("base_id"), x["data"].get("image")) for x in sug[:6]])
    except Exception as e: print(t, "ERR", e)
