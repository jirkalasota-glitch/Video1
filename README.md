# Přehrávač

Android přehrávač videa, který přehraje prakticky cokoli (jádro mpv + FFmpeg) a umí diagnostikovat a opravovat poškozená videa.

## Stažení
Nejnovější APK je vždy v sekci **Releases** → `prehravac.apk`.

## Co umí (verze 0.1)
- Přehraje MKV, MP4, AVI, WMV, FLV, WebM, TS, HEVC, AV1, VP9… i stylované titulky (ASS)
- Rozbitá místa ve videu přeskočí místo pádu
- Gesta: dvojklep vlevo/vpravo = ±10 s, tah vodorovně = posun, vlevo svisle = jas, vpravo svisle = hlasitost, podržení = 2× rychlost
- Pamatuje si, kde jsi skončil
- Vyrovnání hlasitosti (tiché dialogy / hlasité výbuchy), časovač spánku, smyčka A-B, krokování po snímcích
- **Diagnostika a oprava**: chybějící index, useknutý soubor, rozbité časové značky, nepodporovaný kodek → oprava do nového MP4 (originál zůstane)
