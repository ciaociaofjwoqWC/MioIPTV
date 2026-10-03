# Prepara channels.json: l'elenco canali con PIÙ sorgenti per ogni canale.
# La gira l'automatismo di GitHub (.github/workflows/update-epg.yml) ogni 6 ore;
# si può lanciare anche a mano:  python tools/build_channels.py
#
# I link gratuiti dei canali cambiano o smettono di funzionare senza avviso.
# Per questo si parte dalla playlist principale e per ogni canale si aggiungono
# i link alternativi trovati in altre playlist pubbliche: se il primo non va,
# l'app prova da sola il successivo (e si ricorda quale ha funzionato).
import json, re, urllib.request
from pathlib import Path

MAIN = "https://raw.githubusercontent.com/Tundrak/IPTV-Italia/main/iptvitaplus.m3u"
# In ordine di preferenza: Free-TV è curata a mano e aggiornata spesso.
EXTRA = [
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_italy.m3u8",
    "https://iptv-org.github.io/iptv/countries/it.m3u",
]
OUT = Path(__file__).resolve().parent.parent / "channels.json"


def norm(s):
    # Deve restare identica a norm() in index.html.
    t = re.sub(r"[^a-z0-9]", "", (s or "").lower())
    if len(t) > 4 and t.endswith("it"):
        t = t[:-2]
    return t


def clean_name(s):
    # "Rai 1 (720p) [Geo-blocked]" -> "Rai 1"
    return re.sub(r"\s*[\(\[][^\)\]]*[\)\]]", "", s).strip()


def keys_of(c):
    # Più modi di chiamare lo stesso canale: id, nome, e il nome prima di un
    # trattino ("HGTV – Home & Garden Tv" e "HGTV - Home&Garden" -> hgtv).
    name = clean_name(c["name"])
    ks = [norm(c["id"]), norm(name), norm(re.split(r"\s[-–]\s", name)[0])]
    ks += [re.sub(r"hd$", "", k) for k in ks]  # "Rai Sport + HD" -> raisport
    return [k for k in dict.fromkeys(ks) if k]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def parse(text):
    out, cur = [], {}
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            attr = lambda k: (re.search(k + r'="([^"]*)"', line) or [None, ""])[1]
            cur = {
                "name": line.split(",", 1)[1].strip() if "," in line else "",
                "logo": attr("tvg-logo"), "group": attr("group-title") or "Altro",
                "id": attr("tvg-id").split("@")[0], "number": int(attr("tvg-chno") or 0),
                "ua": "", "ref": "",
            }
        elif line.lower().startswith("#extvlcopt:http-user-agent="):
            cur["ua"] = line.split("=", 1)[1].strip()
        elif line.lower().startswith("#extvlcopt:http-referrer="):
            cur["ref"] = line.split("=", 1)[1].strip()
        elif line and not line.startswith("#") and cur:
            cur["url"] = line
            out.append(cur)
            cur = {}
    return out


def main():
    main_list = parse(get(MAIN))
    alts = {}  # chiave -> [url, ...]
    for src in EXTRA:
        try:
            for c in parse(get(src)):
                for k in keys_of(c):
                    alts.setdefault(k, []).append(c["url"])
        except Exception as e:
            print("KO", src, e)

    channels, n_alt = [], 0
    for c in main_list:
        urls = [c["url"]]
        for k in keys_of(c):
            for u in alts.get(k, []):
                if u not in urls:
                    urls.append(u)
        # Prima i link https: da una pagina https quelli http vengono bloccati.
        urls = urls[:1] + sorted(urls[1:], key=lambda u: not u.startswith("https://"))
        n_alt += len(urls) - 1
        channels.append({
            "name": c["name"], "logo": c["logo"], "group": c["group"], "id": c["id"],
            "number": c["number"], "ua": c["ua"], "ref": c["ref"], "urls": urls,
        })

    OUT.write_text(json.dumps({"channels": channels}, ensure_ascii=False, indent=1), encoding="utf-8")
    print("Scritto", OUT, len(channels), "canali,", n_alt, "sorgenti di riserva")


if __name__ == "__main__":
    main()
