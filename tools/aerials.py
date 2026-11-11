#!/usr/bin/env python3
"""Fetch Apple's Aerial screen-saver catalog (and, optionally, stills for wallpapers) from Apple's server.

  python3 -I tools/aerials.py                 catalog + 16 wallpapers (default)
  python3 -I tools/aerials.py --wallpapers 0  catalog only
  python3 -I tools/aerials.py --lang en       captions in another language Apple provides (ru, en, de, uk, ...)

Writes, on your own computer (these files are not part of the repository, see .gitignore):
  web/data/aerials.js                 window.TVAerials = [{file, name, cat, pois: [[seconds, caption]]}]
                                      window.TVAerialCats = {Landscapes: "...", ...}  (Apple's category names)
  web/assets/wallpapers/<id>.jpg      1920x1080 stills of the films, plus thumb/ (480x270) and preview/ (720x405)
  web/assets/wallpapers/list.js       window.TVWallpapers = [{id, name, place, cat}]

The films themselves are never downloaded: the launcher streams them from Apple while the screen saver runs.
Python standard library only; thumbnails are made with macOS `sips` when it exists (otherwise the launcher makes
them itself). Your own photos can be added to list.js by hand (see web/assets/wallpapers/README.md).
"""
import argparse
import io
import json
import os
import plistlib
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = "https://sylvan.apple.com/Aerials/resources-16.tar"
SNAPSHOT = "https://sylvan.apple.com/Aerials/jjGqDwCNrOsVJ4of6uSVr/snapshots/{}.jpg"

# Our Russian titles for Apple's English film names (Apple's catalog has no localized titles, only captions).
RU = {
    "Southern California to Baja": "Из Южной Калифорнии в Мексику", "Greenland": "Гренландия", "Korea and Japan Night": "Корея и Япония ночью",
    "Los Angeles": "Лос-Анджелес", "London": "Лондон", "Africa Night": "Африка ночью", "China": "Китай", "Sahara and Italy": "Сахара и Италия",
    "New York": "Нью-Йорк", "Italy to Asia": "Из Италии в Азию", "Caribbean": "Карибское море", "San Francisco": "Сан-Франциско",
    "North America Aurora": "Северное сияние над Америкой", "Dubai": "Дубай", "West Africa to the Alps": "Из Западной Африки в Альпы",
    "Iran and Afghanistan": "Иран и Афганистан", "Caribbean Day": "Карибское море днём", "Hawaii": "Гавайи",
    "California to Vegas": "Из Калифорнии в Лас-Вегас", "Ireland to Asia": "Из Ирландии в Азию", "Hong Kong": "Гонконг",
    "Nile Delta": "Дельта Нила", "New Zealand": "Новая Зеландия", "Liwa": "Лива", "South Africa to North Asia": "Из Южной Африки в Северную Азию",
    "Atlantic Ocean to Spain and France": "Над Атлантикой к Испании и Франции", "Antarctica": "Антарктида",
    "Africa and the Middle East": "Африка и Ближний Восток", "New York Night": "Нью-Йорк ночью", "Australia": "Австралия",
    "Grand Canyon": "Гранд-Каньон", "Patagonia": "Патагония", "Yosemite": "Йосемити", "Iceland": "Исландия", "Scotland": "Шотландия",
    "Seals": "Тюлени", "Palau Jellies": "Медузы Палау", "Palau Coral": "Кораллы Палау", "Kelp": "Подводный лес", "Barracuda": "Барракуды",
    "Sea Stars": "Морские звёзды", "Bumpheads": "Рыбы-попугаи", "Jacks": "Каранксы", "Humpback Whale": "Горбатый кит",
    "California Dolphins": "Дельфины Калифорнии", "Tahiti Waves": "Волны Таити", "Red Sea Coral": "Кораллы Красного моря",
    "Cownose Rays": "Скаты", "California Kelp Forest": "Подводный лес Калифорнии", "Alaskan Jellies": "Медузы Аляски",
    "Gray Reef Sharks": "Серые рифовые акулы", "Costa Rica Dolphins": "Дельфины Коста-Рики",
}
# Stills used as wallpapers, in this order (the first one is the default wallpaper).
WALLPAPERS = [
    "GL_G010_C006", "H004_C009", "CH_C007_C011", "LW_L001_C006", "A105_C003_0212CT", "GL_G004_C010", "H012_C009_0", "DB_D011_C010",
    "HK_H004_C010", "N008_C009", "LA_A009_C009", "GMT110_112NC_364D_1054_AURORA_ANTARCTICA", "GMT314_139M_170NC_NORTH_AMERICA_AURORA",
    "GMT026_363A_103NC_E1027_KOREA_JAPAN_NIGHT", "PA_A001_C007", "SE_A016_C009",
]


def fetch(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "tv-os-aerials"}), timeout=60) as r:
            return r.read()
    except Exception:  # e.g. a Python without its CA certificates: fall back to curl
        return subprocess.run(["curl", "-fsSL", url], check=True, capture_output=True).stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lang", default="ru")
    ap.add_argument("--wallpapers", type=int, default=len(WALLPAPERS))
    a = ap.parse_args()

    print("Каталог Apple Aerial…")
    tar = tarfile.open(fileobj=io.BytesIO(fetch(CATALOG)))
    entries = json.load(tar.extractfile("entries.json"))
    names = [m for m in tar.getnames() if m.endswith(f"/{a.lang}.lproj/Localizable.nocache.strings")]
    strings = plistlib.load(tar.extractfile(names[0])) if names else {}
    cats = {c["id"]: c["localizedNameKey"].replace("AerialCategory", "") for c in entries["categories"]}
    cat_names = {c["localizedNameKey"].replace("AerialCategory", ""): strings.get(c["localizedNameKey"], c["localizedNameKey"])
                 for c in entries["categories"]}
    title = (lambda s: RU.get(s, s)) if a.lang == "ru" else (lambda s: s)

    films, shots = [], {}
    for x in entries["assets"]:
        url = x.get("url-1080-H264")
        if not url:
            continue
        cat = cats.get((x.get("categories") or [""])[0], "")
        pois = sorted((int(t), strings.get(k, "")) for t, k in (x.get("pointsOfInterest") or {}).items())
        films.append({"file": url.rsplit("/", 1)[1], "name": title(x["accessibilityLabel"]), "cat": cat, "pois": [[t, c] for t, c in pois if c]})
        shots[x["shotID"]] = (title(x["accessibilityLabel"]), cat)
    out = os.path.join(ROOT, "web", "data", "aerials.js")
    with open(out, "w", encoding="utf-8") as f:
        f.write("/* Generated by tools/aerials.py from Apple's Aerial catalog. Not part of the repository. */\n")
        f.write("window.TVAerialCats = " + json.dumps(cat_names, ensure_ascii=False) + ";\n")
        f.write("window.TVAerials = " + json.dumps(films, ensure_ascii=False, indent=0) + ";\n")
    print(f"  {len(films)} видео → web/data/aerials.js")

    if a.wallpapers <= 0:
        return
    wdir = os.path.join(ROOT, "web", "assets", "wallpapers")
    for d in ("thumb", "preview"):
        os.makedirs(os.path.join(wdir, d), exist_ok=True)
    sips = shutil.which("sips")
    listed = []
    list_js = os.path.join(wdir, "list.js")
    if os.path.exists(list_js):  # keep photos added by hand
        try:
            text = open(list_js, encoding="utf-8").read()
            listed = [w for w in json.loads(text[text.index("["):text.rindex("]") + 1]) if not w.get("aerial")]
        except (ValueError, json.JSONDecodeError):
            listed = []
    aerial = []
    for shot in WALLPAPERS[: a.wallpapers]:
        if shot not in shots:
            continue
        name, cat = shots[shot]
        wid = "aerial-" + shot.lower().replace("_", "-")
        path = os.path.join(wdir, wid + ".jpg")
        if not os.path.exists(path):
            print("  фон:", name)
            try:
                data = fetch(SNAPSHOT.format(shot))
            except Exception:
                print("    нет снимка у Apple, пропускаю")
                continue
            open(path, "wb").write(data)
        ok = True
        for sub, w, h in (("thumb", 480, 270), ("preview", 720, 405)):
            dst = os.path.join(wdir, sub, wid + ".jpg")
            if not os.path.exists(dst):
                ok = ok and bool(sips) and subprocess.run([sips, "-s", "format", "jpeg", "-s", "formatOptions", "80", "--resampleHeightWidth",
                                                            str(h), str(w), path, "--out", dst], capture_output=True).returncode == 0
        entry = {"id": wid, "name": name, "place": "Apple TV Aerial · " + cat_names.get(cat, cat), "cat": cat, "aerial": True}
        if not ok:
            entry["thumb"] = False
        aerial.append(entry)
    with open(list_js, "w", encoding="utf-8") as f:
        f.write("/* Wallpapers on this computer. Made by tools/aerials.py; photos added by hand stay. Not part of the repository. */\n")
        f.write("window.TVWallpapers = " + json.dumps(listed + aerial, ensure_ascii=False, indent=0) + ";\n")
    print(f"  {len(aerial)} фонов из заставки → web/assets/wallpapers/")


if __name__ == "__main__":
    sys.exit(main())
