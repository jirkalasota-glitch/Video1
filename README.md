# Přehrávač

Android přehrávač videa, který přehraje prakticky cokoli (jádro mpv + FFmpeg) a umí diagnostikovat a opravovat poškozená videa.

## Stažení
Nejnovější APK je vždy v sekci **Releases** → `prehravac.apk`.

## Co umí (verze 0.3)
- Přehraje MKV, MP4, AVI, WMV, FLV, WebM, TS, HEVC, AV1, VP9… i stylované titulky (ASS)
- Rozbitá místa ve videu přeskočí místo pádu
- Gesta: dvojklep vlevo/vpravo = ±10 s, tah vodorovně = posun, vlevo svisle = jas, vpravo svisle = hlasitost, podržení = 2× rychlost
- Pamatuje si, kde jsi skončil
- Vyrovnání hlasitosti (tiché dialogy / hlasité výbuchy), časovač spánku, smyčka A-B, krokování po snímcích
- **Diagnostika a oprava**: chybějící index, useknutý soubor, rozbité časové značky, nepodporovaný kodek → oprava do nového MP4 (originál zůstane)
- **Obnova chybějícího indexu (moov)** – video, které se nedonahrálo nebo nedostáhlo: automaticky (když jsou nastavení kodeku v datech), nebo pomocí referenčního videa ze stejného zdroje. H.264 i H.265, správné pořadí B-snímků. Zvuk zatím ne.
- **Přehrávání z internetu** – tlačítko „Z internetu“: vlož odkaz na video nebo stránku s videem (YouTube, Vimeo, TikTok, X, zpravodajské weby… – vše, co umí yt-dlp). Odkaz ze schránky se předvyplní sám. Funguje i **Sdílet → Přehrát v Přehrávači** z prohlížeče nebo jiné aplikace. Pamatuje si pozici i u webových videí. yt-dlp se samo aktualizuje (1× denně, nebo podržením tlačítka „Z internetu“).
