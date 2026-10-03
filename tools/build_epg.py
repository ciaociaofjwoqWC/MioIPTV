# Prepara epg.json: la guida programmi già filtrata sui canali della playlist.
# La gira l'automatismo di GitHub (.github/workflows/update-epg.yml) ogni 6 ore;
# si può lanciare anche a mano:  python tools/build_epg.py
#
# Perché un file già pronto invece dell'XML originale: la fonte (epgshare01) è
# ~13 MB con centinaia di canali che non ci servono. Leggerla e analizzarla
# nel browser della chiavetta TV richiede decine di secondi; epg.json pesa
# poche centinaia di KB e contiene solo i nostri canali e le prossime ore.
import gzip, json, re, time, urllib.request, xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

PLAYLIST_URL = "https://raw.githubusercontent.com/Tundrak/IPTV-Italia/main/iptvitaplus.m3u"
EPG_URLS = ["https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"]
OUT = Path(__file__).resolve().parent.parent / "epg.json"
# Canali che nella guida hanno un nome troppo diverso da quello della playlist.
ALIASES = {"20mediaset": "20", "twentyseven": "27twentyseven"}


def norm(s):
    # Deve restare identica a norm() in index.html.
    t = re.sub(r"[^a-z0-9]", "", (s or "").lower())
    if len(t) > 4 and t.endswith("it"):
        t = t[:-2]
    return t


def variants(cid, name):
    # "LA7 HD" -> la7 · "Rai 4  5021" -> rai4 · "TV8.HD.it" -> tv8
    out = []
    for s in (cid, name, (name or "").split("  ")[0]):
        n = norm(s)
        for v in (n, re.sub(r"hd\d*$", "", n)):
            if v and v not in out:
                out.append(v)
    return out


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def parse_t(s):
    return int(datetime.strptime(s.strip(), "%Y%m%d%H%M%S %z").timestamp() * 1000)


def main():
    playlist = get(PLAYLIST_URL).decode("utf-8", "replace")
    wanted = {}  # chiave -> chiave principale del canale
    for m in re.finditer(r"#EXTINF[^\n]*", playlist):
        line = m.group(0)
        cid = (re.search(r'tvg-id="([^"]*)"', line) or [None, ""])[1]
        name = line.split(",", 1)[1].strip() if "," in line else ""
        keys = [k for k in (norm(cid), norm(name)) if k]
        if not keys:
            continue
        for k in keys:
            wanted.setdefault(k, keys[0])
            if k in ALIASES:
                wanted.setdefault(ALIASES[k], keys[0])

    now = time.time() * 1000
    t_from, t_to = now - 3 * 3600e3, now + 48 * 3600e3
    result = {}
    for url in EPG_URLS:
        try:
            root = ET.fromstring(get(url))
        except Exception as e:
            print("KO", url, e)
            continue
        # Prima i nomi identici, poi le varianti (senza "HD" ecc.), così un
        # canale "Rai1" esatto vince su "Rai 1 HD 101".
        id_key, cands = {}, []
        for ch in root.iter("channel"):
            dn = ch.find("display-name")
            cands.append((ch.get("id"), variants(ch.get("id"), dn.text if dn is not None else "")))
        taken = set(result)
        for level in (0, 1, 2, 3, 4, 5):
            for cid, vs in cands:
                if cid in id_key or len(vs) <= level:
                    continue
                k = wanted.get(vs[level])
                if k and k not in taken:
                    id_key[cid] = k
                    taken.add(k)
        for p in root.iter("programme"):
            k = id_key.get(p.get("channel"))
            if not k:
                continue
            s, e = parse_t(p.get("start", "")), parse_t(p.get("stop", ""))
            if not (e > t_from and s < t_to):
                continue
            title = (p.findtext("title") or "").strip()
            desc = (p.findtext("desc") or "").strip()[:300]
            result.setdefault(k, []).append([s, e, title, desc])
        print("OK", url, len(id_key), "canali")

    for k in result:
        result[k].sort()
    OUT.write_text(json.dumps({"generated": int(now), "channels": result}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print("Scritto", OUT, len(result), "canali,", OUT.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
