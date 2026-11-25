"""
NovaPlay server (a Hugging Face Space). Does the heavy work the TV and phones did themselves:

  * TV guide: your provider's guide plus the kept public guides, cut down to YOUR channels and the next day and a
    half, as one small file the app loads like any guide:            /k/<key>/guide.xml.gz
  * Addons: your Stremio addons' live-event catalogs and streams, read here every 10 minutes and served as one
    addon the app adds like any other:                               /k/<key>/manifest.json
  * Status:                                                          /k/<key>/status

Refreshes: playlist and guides at 05:00, 12:00 and 18:00 (New York time) and at every start; addons every 10 min.
A refresh builds a new copy on the side and swaps it in when complete, so the app never sees half of one.

Secrets (Settings -> Variables and secrets): IPTV_HOST, IPTV_USER, IPTV_PASS, ADDONS, SERVER_KEY.
"""
import os, re, gzip, json, time, difflib, threading, traceback, calendar, urllib.request, urllib.parse
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from zoneinfo import ZoneInfo

# No server-side rendering: it starts a second web server on port 7860, which the app below needs.
os.environ["GRADIO_SSR_MODE"] = "false"
import gradio as gr
import spaces
import uvicorn
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse


@spaces.GPU
def unused():  # ZeroGPU Spaces need one of these to start; nothing here uses it.
    return 0


KEY = os.environ.get("SERVER_KEY", "").strip()
DATA = "/tmp/novaplay"
os.makedirs(DATA, exist_ok=True)
TZ = ZoneInfo("America/New_York")
GUIDE_HOURS = (5, 12, 18)
UA = "Mozilla/5.0 (Linux; Android 11) NovaPlay/1.0"

# The guides kept after the 51-guide check (all of them together fill the same channels as the 51).
GUIDES = """
https://epgshare01.online/epgshare01/epg_ripper_ALL_SOURCES1.xml.gz
https://iptv-epg.org/files/epg-us.xml.gz
https://iptv-epg.org/files/epg-in.xml.gz
https://iptv-epg.org/files/epg-gb.xml.gz
https://iptv-epg.org/files/epg-es.xml.gz
https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/Sports_guide.xml.gz
https://iptv-epg.org/files/epg-ca.xml.gz
https://iptv-epg.org/files/epg-de.xml.gz
https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz
https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/UK_guide.xml.gz
https://free-epg.de/api/epg/fr.xml.gz
https://epg.pw/xmltv/epg.xml.gz
""".split()

state = {
    "started": time.time(),
    "playlist": {"channels": 0, "at": None, "error": None},
    "guide": {"at": None, "secs": None, "channels": 0, "programmes": 0, "bytes": 0, "error": None, "running": False, "sources": []},
    "addons": {"at": None, "secs": None, "catalogs": 0, "events": 0, "streams_cached": 0, "error": None, "running": False, "sources": []},
}


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def open_url(url, timeout=60, headers=None):
    h = {"User-Agent": UA}
    h.update(headers or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)


def get_json(url, timeout=30):
    with open_url(url, timeout) as r:
        return json.loads(r.read().decode("utf8", "replace"))


# ---------------------------------------------------------------- playlist

QUALITY = re.compile(r"\b(hd|fhd|uhd|sd|4k|hevc|h265|raw|backup|vip|\d{3,4}p)\b")


def norm(name):
    n = (name or "").lower()
    n = re.sub(r"^\s*[\w+]{2,5}\s*[:|]\s*", "", n)
    n = re.sub(r"[\[(].*?[\])]", " ", n)
    n = re.sub(r"\.[a-z]{2}$", "", n)
    n = QUALITY.sub(" ", n)
    return re.sub(r"[^a-z0-9]", "", n)


def provider(path):
    host = os.environ["IPTV_HOST"].rstrip("/")
    q = urllib.parse.urlencode({"username": os.environ["IPTV_USER"], "password": os.environ["IPTV_PASS"]})
    return f"{host}/{path}?{q}"


name_countries = {}  # bare name -> countries your channels of that name are from


def country(code):
    c = (code or "").lower()
    return {"uk": "gb"}.get(c, c)


def country_of_name(name):
    """"DE: TLC HD" -> "de"; "VIP: TLC" -> None (a provider's tag, not a country)."""
    m = re.match(r"\s*([A-Za-z]{2})\s*[:|]", name or "")
    return country(m.group(1)) if m else None


def country_of_guide_id(cid):
    """"TLC.de" -> "de"; "SkyDocumentaries.uk" -> "gb"; no country -> None."""
    m = re.search(r"\.([a-z]{2})$", (cid or "").lower())
    return country(m.group(1)) if m else None


def playlist_keys():
    """Your channels' guide ids ("id:...") and bare names: what a guide channel must match to be kept."""
    with open_url(provider("player_api.php") + "&action=get_live_streams", 120) as r:
        streams = json.load(r)
    keys = set()
    name_countries.clear()
    for s in streams:
        k = norm(s.get("name"))
        if k:
            keys.add(k)
            c = country_of_name(s.get("name"))
            if c:
                name_countries.setdefault(k, set()).add(c)
        e = (s.get("epg_channel_id") or "").strip().lower()
        if e:
            keys.add("id:" + e)
    state["playlist"].update(channels=len(streams), at=time.time(), error=None)
    return keys


# ---------------------------------------------------------------- guide

def utc_secs(t):
    """XMLTV time ("20261125143000 +0000") as seconds since 1970, or None."""
    t = (t or "").strip()
    if len(t) < 14:
        return None
    try:
        secs = calendar.timegm(time.strptime(t[:14], "%Y%m%d%H%M%S"))
    except ValueError:
        return None
    m = re.search(r"([+-])(\d{2})(\d{2})$", t)
    if m:
        off = (int(m.group(2)) * 60 + int(m.group(3))) * 60
        secs -= off if m.group(1) == "+" else -off
    return secs


def element_xml(el, keep_children):
    """An element written back out, with only the child tags in [keep_children] (descriptions shortened)."""
    attrs = "".join(f" {k}={quoteattr(v)}" for k, v in el.attrib.items())
    inner = []
    for c in el:
        if c.tag not in keep_children:
            continue
        text = (c.text or "").strip()
        if c.tag == "desc" and len(text) > 300:
            text = text[:297] + "..."
        cattrs = "".join(f" {k}={quoteattr(v)}" for k, v in c.attrib.items())
        inner.append(f"<{c.tag}{cattrs}>{escape(text)}</{c.tag}>" if text else f"<{c.tag}{cattrs}/>")
    return f"<{el.tag}{attrs}>{''.join(inner)}</{el.tag}>\n"


def xmltv_time(secs):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(secs))


def add_guide(url, wanted, covered, emitted_ids, out, window, shifts=None, route=None):
    """
    Adds [url]'s channels that fill one of your channels no earlier guide filled, and their shows in [window].
    [shifts]: seconds to move each channel's shows by ("id:..." -> seconds), for a guide whose times are off.
    A channel matched by name only is skipped when it is another country's ("TLC.de" for your UK TLC).
    [route]: channels to take from one guide only (your channel -> that guide's address): the others skip them.
    """
    t0 = time.time()
    info = {"url": url.split("?")[0], "channels": 0, "programmes": 0, "secs": 0, "error": None}
    keep = {}
    info["shifted_channels"] = 0
    info["other_country_skipped"] = 0
    try:
        with open_url(url, 120) as r:
            raw = r
            stream = gzip.GzipFile(fileobj=raw) if raw.peek(2)[:2] == b"\x1f\x8b" else raw
            for _, el in ET.iterparse(stream, events=("end",)):
                if el.tag == "channel":
                    cid = el.get("id") or ""
                    ks = {"id:" + cid.lower(), norm(cid)} | {norm(d.text) for d in el.findall("display-name")}
                    mine = {k for k in ks if k and k in wanted}
                    if mine and not any(k.startswith("id:") for k in mine):
                        gc = country_of_guide_id(cid)
                        yours = set().union(*[name_countries.get(k, set()) for k in mine])
                        if gc and yours and gc not in yours:
                            info["other_country_skipped"] += 1
                            mine = set()
                    if mine and route and any(k in route and route[k] != url for k in mine):
                        mine = set()
                    if mine and not mine <= covered and cid not in emitted_ids:
                        covered |= mine
                        emitted_ids.add(cid)
                        keep[cid] = (shifts or {}).get("id:" + cid.lower(), 0)
                        if keep[cid]:
                            info["shifted_channels"] += 1
                        out.write(element_xml(el, {"display-name", "icon"}))
                        info["channels"] += 1
                    el.clear()
                elif el.tag == "programme":
                    if el.get("channel") in keep:
                        start, stop = utc_secs(el.get("start")), utc_secs(el.get("stop"))
                        if start is not None and stop is not None and stop >= window[0] and start <= window[1]:
                            shift = keep[el.get("channel")]
                            if shift:
                                el.set("start", xmltv_time(start + shift))
                                el.set("stop", xmltv_time(stop + shift))
                            out.write(element_xml(el, {"title", "sub-title", "desc", "category", "episode-num", "live", "new"}))
                            info["programmes"] += 1
                    el.clear()
                if time.time() - t0 > 900:
                    raise TimeoutError("over 15 minutes")
    except Exception as e:
        info["error"] = f"{type(e).__name__}: {str(e)[:120]}"
    info["secs"] = round(time.time() - t0)
    return info


SHIFTS_H = (-3, -2, -1, -0.5, 0.5, 1, 2, 3)
PLACEHOLDER = re.compile(r"to be announced|\btba\b|\btbc\b|coming soon|no longer available|off air|no data|"
                         r"no information|no programme|programmes? coming|^n/?a$|^close$|^closedown$", re.I)


def family(url):
    """Guides from one site share their data: they count as one voice."""
    return url.split("//", 1)[-1].split("/")[0]


def is_placeholder(title, key):
    t = title_key(title)
    own = norm(key[3:].split(".")[0] if key.startswith("id:") else key)
    return not t or bool(PLACEHOLDER.search(title or "")) or (own and norm(title) == own)


def agreement(a, b, times):
    """Share of [times] at which schedules [a] and [b] show the same thing (None: under 4 to compare)."""
    n = same = 0
    for t in times:
        ta, tb = on_at(a, t), on_at(b, t)
        if ta and tb:
            n += 1
            same += same_show(ta, tb)
    return same / n if n >= 4 else None


def provider_review(wanted, now):
    """
    Checks your provider's guide against the others, channel by channel, and returns:
      shifts:  channels whose times are off by the same amount (local times marked as UTC) -> seconds to move;
      route:   channels better taken from another guide -> (that guide, why):
               * placeholders: your provider shows only the channel's name, "To be announced", "coming soon"...
                 while another guide has real shows;
               * a clear majority: two or more guides from different sites (ALL_SOURCES1, built from others, not
                 counted) agree with each other and not with your provider.
    One guide against your provider is not a majority: your provider's is kept.
    """
    t_from, t_to = now - 6 * 3600, now + 18 * 3600
    ref_urls = [u for u in GUIDES if "epg.pw" not in u]
    with ThreadPoolExecutor(4) as pool:
        prov_f = pool.submit(read_shows, provider("xmltv.php"), wanted, t_from, t_to)
        refs = list(pool.map(lambda u: read_shows(u, wanted, t_from, t_to), ref_urls))
    prov = prov_f.result()
    shifts, route = {}, {}
    times = [now - 3 * 3600 + i * 1800 for i in range(30)]  # every half hour from 3 hours ago to 12 hours ahead
    for k, shows in prov.items():
        have = [(u, r[k]) for u, r in zip(ref_urls, refs) if r.get(k)]
        if not have:
            continue
        ref = [x for _, sh in have for x in sh]

        def lined_up(off):
            n = 0
            for st, sp, title in shows:
                t = st + off
                if any(abs(rs - t) <= 600 and same_show(title, rt) for rs, _, rt in ref):
                    n += 1
            return n

        base = lined_up(0)
        best, score = 0, base
        for h in SHIFTS_H:
            n = lined_up(int(h * 3600))
            if n > score:
                best, score = int(h * 3600), n
        if best and score >= 2 and score >= base + 2:
            shifts[k] = best
        mine = [(st + shifts.get(k, 0), sp + shifts.get(k, 0), t) for st, sp, t in shows]

        # Placeholders: most of your provider's shows say nothing, another guide has real ones.
        if sum(is_placeholder(t, k) for _, _, t in mine) >= max(2, len(mine) / 2):
            real = [(sum(not is_placeholder(t, k) for _, _, t in sh), u) for u, sh in have]
            n, u = max(real)
            if n >= 3:
                route[k] = (u, "placeholders")
                continue

        # Majority: guides that disagree with your provider but agree with each other, from 2+ different sites.
        def agrees(a, b):
            x = agreement(a, b, times)
            return None if x is None else x

        against = [(u, sh) for u, sh in have if agrees(mine, sh) is not None and agrees(mine, sh) < 0.3]
        if len(against) < 2:
            continue
        for u, sh in against:
            group = [(v, sv) for v, sv in against if v == u or (agrees(sh, sv) or 0) >= 0.7]
            sites = {family(v) for v, _ in group if "ALL_SOURCES" not in v}
            if len(sites) >= 2:
                pick = max(group, key=lambda g: (len(g[1]), "ALL_SOURCES" not in g[0]))[0]
                route[k] = (pick, f"majority of {len(group)} guides")
                break
    log(f"provider review: {len(shifts)} time fixes, {len(route)} channels taken from other guides")
    return shifts, route


def build_guide():
    g = state["guide"]
    if g["running"]:
        return
    g["running"] = True
    t0 = time.time()
    try:
        wanted = playlist_keys()
        now = time.time()
        window = (now - 3 * 3600, now + 36 * 3600)
        shifts, routes = provider_review(wanted, now)
        route = {k: u for k, (u, why) in routes.items()}
        g["provider_replaced"] = {"placeholders": sum(1 for _, w in routes.values() if w == "placeholders"),
                                  "majority": sum(1 for _, w in routes.values() if w != "placeholders"),
                                  "channels": [f"{k[3:] if k.startswith('id:') else k}: {why} -> {family(u)}/{u.rsplit('/', 1)[-1]}"
                                               for k, (u, why) in sorted(routes.items())][:300]}
        g["provider_time_fixes"] = {f"{h:+g}h": sum(1 for v in shifts.values() if v == h * 3600)
                                    for h in sorted({v / 3600 for v in shifts.values()})}
        tmp = f"{DATA}/guide.new.xml.gz"
        covered, emitted, sources = set(), set(), []
        with gzip.open(tmp, "wt", encoding="utf8", compresslevel=6) as out:
            out.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="NovaPlay server">\n')
            # Your provider's guide first: its channels are yours by definition.
            for url in [provider("xmltv.php")] + GUIDES:
                info = add_guide(url, wanted, covered, emitted, out, window,
                                 shifts if url.startswith(os.environ["IPTV_HOST"]) else None, route)
                if url.startswith(os.environ["IPTV_HOST"]):
                    info["url"] = "your provider's guide"
                sources.append(info)
                log(f"guide {info['url']}: {info['channels']} channels, {info['programmes']} shows, {info['secs']}s {info['error'] or ''}")
            out.write("</tv>\n")
        os.replace(tmp, f"{DATA}/guide.xml.gz")  # the swap: the app gets the old file or the new one, never half
        g.update(at=time.time(), secs=round(time.time() - t0), channels=len(emitted),
                 programmes=sum(s["programmes"] for s in sources), bytes=os.path.getsize(f"{DATA}/guide.xml.gz"),
                 error=None, sources=sources)
    except Exception as e:
        g["error"] = f"{type(e).__name__}: {e}"
        log("guide failed: " + traceback.format_exc())
    finally:
        g["running"] = False


# ---------------------------------------------------------------- addons

# The addon this server presents (its catalogs mirror your addons' event catalogs) and what it last read.
addon_view = {"manifest": None, "catalogs": {}, "routes": {}}
stream_cache = {}  # "a<i>~<event id>" -> (when, streams)
STREAM_KEEP = 10 * 60
MATCH = re.compile(r"\s(vs\.?|v|x|@|-|–)\s", re.I)


def addon_links():
    return os.environ.get("ADDONS", "").split()


def base_of(manifest):
    return manifest.rsplit("/manifest.json", 1)[0]


def read_addon(i, manifest):
    """One addon's event catalogs: (manifest name, [(catalog id, name, metas)])."""
    m = get_json(manifest, 60)
    tv = [c for c in m.get("catalogs", []) if c.get("type") == "tv"]
    events = [c for c in tv if "event" in (c.get("id", "") + c.get("name", "")).lower()] or tv
    out = []
    for c in events:
        try:
            cid = c["id"]
            metas = get_json(f"{base_of(manifest)}/catalog/tv/{urllib.parse.quote(cid, safe='')}.json", 60).get("metas", [])
            slim = []
            for meta in metas:
                name = meta.get("name") or ""
                vid = (meta.get("behaviorHints") or {}).get("defaultVideoId") or meta.get("id")
                # Only matches with two sides ("India vs New Zealand"): the only ones the app can tie to a game.
                if vid and MATCH.search(name):
                    slim.append({"id": f"a{i}~{vid}", "type": "tv", "name": name})
            out.append((cid, c.get("name") or cid, slim))
        except Exception as e:
            log(f"addon {i} catalog {c.get('id')}: {type(e).__name__}: {e}")
    return m.get("name") or "Addon", m.get("logo"), out


def build_addons():
    a = state["addons"]
    if a["running"]:
        return
    a["running"] = True
    t0 = time.time()
    try:
        links = addon_links()
        results = {}
        with ThreadPoolExecutor(4) as pool:
            futures = {i: pool.submit(read_addon, i, link) for i, link in enumerate(links)}
            for i, f in futures.items():
                try:
                    results[i] = f.result()
                except Exception as e:
                    log(f"addon {i} failed: {type(e).__name__}: {e}")
        old = addon_view["catalogs"]
        catalogs, manifest_cats, sources, logo = {}, [], [], None
        for i, link in enumerate(links):
            if i not in results:
                # Failed this time: keep what it gave last time.
                for cid, cat in old.items():
                    if cid.startswith(f"a{i}~"):
                        catalogs[cid] = cat
                        manifest_cats.append({"type": "tv", "id": cid, "name": cat["name"]})
                sources.append({"addon": i + 1, "error": "kept last good list"})
                continue
            name, alogo, cats = results[i]
            logo = logo or alogo
            n = 0
            for cid, cname, metas in cats:
                key = f"a{i}~{cid}"
                catalogs[key] = {"name": f"{cname} · {name}", "metas": metas}
                manifest_cats.append({"type": "tv", "id": key, "name": f"{cname} · {name}"})
                n += len(metas)
            sources.append({"addon": i + 1, "name": name, "catalogs": len(cats), "matches": n})
        addon_view["manifest"] = {
            "id": "io.github.novatv.server", "version": "1.0.0", "name": "NovaPlay Server",
            "description": "Your addons, read ahead by your NovaPlay server.", "logo": logo,
            "resources": ["catalog", "stream"], "types": ["tv"], "idPrefixes": ["a"], "catalogs": manifest_cats,
        }
        addon_view["catalogs"] = catalogs  # the swap
        addon_view["routes"] = {i: base_of(link) for i, link in enumerate(links)}
        cutoff = time.time() - STREAM_KEEP
        for k in [k for k, v in stream_cache.items() if v[0] < cutoff]:
            stream_cache.pop(k, None)
        a.update(at=time.time(), secs=round(time.time() - t0), catalogs=len(catalogs),
                 events=sum(len(c["metas"]) for c in catalogs.values()), streams_cached=len(stream_cache),
                 error=None, sources=sources)
        log(f"addons: {a['catalogs']} catalogs, {a['events']} matches in {a['secs']}s")
    except Exception as e:
        a["error"] = f"{type(e).__name__}: {e}"
        log("addons failed: " + traceback.format_exc())
    finally:
        a["running"] = False


def streams_for(sid):
    hit = stream_cache.get(sid)
    if hit and time.time() - hit[0] < STREAM_KEEP:
        return hit[1]
    m = re.match(r"a(\d+)~(.+)$", sid)
    base = m and addon_view["routes"].get(int(m.group(1)))
    if not base:
        return []
    try:
        streams = get_json(f"{base}/stream/tv/{urllib.parse.quote(m.group(2), safe='')}.json", 30).get("streams", [])
    except Exception:
        return hit[1] if hit else []
    stream_cache[sid] = (time.time(), streams)
    return streams


# ---------------------------------------------------------------- guide cross-check

cross = {"running": False, "at": None, "report": None, "error": None}
CHECK_HOURS = (0, 1, 2, 3)  # compare what each guide says is on now and in 1, 2, 3 hours


def title_key(t):
    t = (t or "").lower()
    t = re.sub(r"^(live|new|en vivo|direct|direkt)\s*[:\-]\s*", "", t)
    t = re.sub(r"\b(live|new|hd|repeat|r)\b", " ", t)
    return " ".join(re.findall(r"[a-z0-9]+", t))


def same_show(a, b):
    a, b = title_key(a), title_key(b)
    if not a or not b:
        return False
    if a == b or a in b or b in a:
        return True
    wa, wb = set(a.split()), set(b.split())
    if len(wa & wb) / max(1, len(wa | wb)) >= 0.5:
        return True
    return difflib.SequenceMatcher(None, a.replace(" ", ""), b.replace(" ", "")).ratio() >= 0.85


def read_shows(url, wanted, t_from, t_to):
    """{your channel key: [(start, stop, title)]} for shows overlapping [t_from, t_to]."""
    out, chan = {}, {}
    try:
        with open_url(url, 120) as r:
            stream = gzip.GzipFile(fileobj=r) if r.peek(2)[:2] == b"\x1f\x8b" else r
            for _, el in ET.iterparse(stream, events=("end",)):
                if el.tag == "channel":
                    cid = el.get("id") or ""
                    ks = ["id:" + cid.lower(), norm(cid)] + [norm(d.text) for d in el.findall("display-name")]
                    mine = [k for k in ks if k and k in wanted]
                    if mine:
                        chan[cid] = mine[0]
                    el.clear()
                elif el.tag == "programme":
                    k = chan.get(el.get("channel"))
                    if k:
                        st, sp = utc_secs(el.get("start")), utc_secs(el.get("stop"))
                        if st is not None and sp is not None and sp >= t_from and st <= t_to:
                            out.setdefault(k, []).append((st, sp, el.findtext("title") or ""))
                    el.clear()
    except Exception as e:
        log(f"cross-check {url[:60]}: {type(e).__name__}: {e}")
    return out


def on_at(shows, t):
    for st, sp, title in shows:
        if st <= t < sp:
            return title
    return None


def run_crosscheck():
    if cross["running"]:
        return
    cross.update(running=True, error=None)
    try:
        wanted = playlist_keys()
        now = time.time()
        names = ["your provider's guide"] + [u.split("//", 1)[1] for u in GUIDES]
        urls = [provider("xmltv.php")] + GUIDES
        with ThreadPoolExecutor(4) as pool:
            guides = list(pool.map(lambda u: read_shows(u, wanted, now - 4 * 3600, now + 8 * 3600), urls))
        per = {n: {"compared": 0, "agree": 0, "shifted": {}, "differ": 0} for n in names}
        pair_offsets = {}
        examples = []
        channels_compared = 0
        keys = set().union(*[g.keys() for g in guides])
        for k in keys:
            have = [(n, g[k]) for n, g in zip(names, guides) if k in g]
            if len(have) < 2:
                continue
            channels_compared += 1
            for i in range(len(have)):
                for j in range(i + 1, len(have)):
                    (na, sa), (nb, sb) = have[i], have[j]
                    for h in CHECK_HOURS:
                        t = now + h * 3600
                        ta, tb = on_at(sa, t), on_at(sb, t)
                        if not ta or not tb:
                            continue
                        for n in (na, nb):
                            per[n]["compared"] += 1
                        if same_show(ta, tb):
                            per[na]["agree"] += 1; per[nb]["agree"] += 1
                            continue
                        # Same show at another time in B: B (or A) runs early/late by that much.
                        shift = None
                        for off in (-3, -2, -1, -0.5, 0.5, 1, 2, 3):
                            if same_show(ta, on_at(sb, t + off * 3600)):
                                shift = off
                                break
                        if shift is not None:
                            key = f"{na} vs {nb}"
                            pair_offsets.setdefault(key, {}).setdefault(str(shift), 0)
                            pair_offsets[key][str(shift)] += 1
                            for n in (na, nb):
                                per[n]["shifted"][str(shift)] = per[n]["shifted"].get(str(shift), 0) + 1
                        else:
                            per[na]["differ"] += 1; per[nb]["differ"] += 1
                            if len(examples) < 60 and h == 0:
                                examples.append({"channel": k, na: ta, nb: tb})
        rows = []
        for n in names:
            p = per[n]
            if p["compared"]:
                rows.append({"guide": n, "compared": p["compared"], "agree_pct": round(100 * p["agree"] / p["compared"]),
                             "time_shifted": sum(p["shifted"].values()), "shifts": p["shifted"],
                             "different_show_pct": round(100 * p["differ"] / p["compared"])})
        rows.sort(key=lambda r: -r["agree_pct"])
        top_offsets = sorted(((sum(v.values()), k, v) for k, v in pair_offsets.items()), reverse=True)[:15]
        cross["report"] = {"channels_in_2plus_guides": channels_compared,
                           "channels_per_guide": {n: len(g) for n, g in zip(names, guides)},
                           "guides": rows,
                           "biggest_time_shifts": [{"pair": k, "count": c, "by_hours": v} for c, k, v in top_offsets],
                           "examples_of_different_shows_now": examples}
        cross["at"] = time.time()
    except Exception as e:
        cross["error"] = f"{type(e).__name__}: {e}"
        log("cross-check failed: " + traceback.format_exc())
    finally:
        cross["running"] = False


# ---------------------------------------------------------------- game alerts (pushed to phones)

ESPN = "https://site.api.espn.com/apis/site/v2/sports"
devices = {}   # push token -> {"score": bool, "end": bool, "games": {match id: game}, "at": seconds}
watched = {}   # match id -> what was last seen: {"state": "pre"/"in"/"post", "scores": {...}, "wickets": {...}}
alerts_sent = {"count": 0, "last": None, "error": None}
_fcm = {"creds": None}


def fcm_send(token, title, body, key):
    """Sends one notification to a phone through Firebase (FIREBASE_SA: the service account's JSON). False: not sent."""
    sa = (os.environ.get("FIREBASE_SA") or os.environ.get("FIREBASE"))
    if not sa:
        return False
    try:
        from google.oauth2 import service_account
        import google.auth.transport.requests
        if _fcm["creds"] is None:
            _fcm["creds"] = service_account.Credentials.from_service_account_info(
                json.loads(sa), scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        c = _fcm["creds"]
        if not c.valid:
            c.refresh(google.auth.transport.requests.Request())
        msg = {"message": {"token": token, "notification": {"title": title, "body": body}, "data": {"key": key},
                           "android": {"priority": "high", "notification": {"channel_id": "games", "tag": key}}}}
        req = urllib.request.Request(f"https://fcm.googleapis.com/v1/projects/{json.loads(sa)['project_id']}/messages:send",
                                     data=json.dumps(msg).encode(), method="POST",
                                     headers={"Authorization": f"Bearer {c.token}", "Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=20).read()
        alerts_sent["count"] += 1
        alerts_sent["last"] = f"{title}: {body}"
        return True
    except urllib.error.HTTPError as e:
        alerts_sent["error"] = f"HTTP {e.code}: {e.read()[:200]!r}"
        if e.code in (400, 404):  # the app was removed or the address changed: forget this device
            devices.pop(token, None)
    except Exception as e:
        alerts_sent["error"] = f"{type(e).__name__}: {e}"
    return False


def game_now(g):
    """A game's state and score from its ESPN feed: (state, [(team, score text)])."""
    o = get_json(f"{ESPN}/{g['path']}/summary?event={g['id']}", 30)
    comp = ((o.get("header") or {}).get("competitions") or [{}])[0]
    state = (((comp.get("status") or {}).get("type") or {}).get("state")) or "pre"
    sides = []
    for c in comp.get("competitors") or []:
        team = (c.get("team") or {}).get("shortDisplayName") or (c.get("team") or {}).get("displayName") or "?"
        sides.append((team, str(c.get("score") or "").strip()))
    return state, sides


def wickets(score):
    """Wickets down in the innings being played: the last "runs/wickets" ("342/10 & 42/2 (17 ov)" -> 2)."""
    m = re.findall(r"(\d+)\s*/\s*(\d+)", score or "")
    return int(m[-1][1]) if m else None


def runs(score):
    m = re.match(r"\s*(\d+)", score or "")
    return int(m.group(1)) if m else None


def score_line(sides):
    return " · ".join(f"{t} {s}".strip() for t, s in sides)


def what_changed(g, before, state, sides):
    """The alerts worth sending for one game's change: [("score"/"end", title, body)]."""
    out = []
    if not before:
        return out
    if state == "post" and before["state"] != "post":
        out.append(("end", f"Result: {g['title']}", score_line(sides)))
        return out
    if state != "in" or before["state"] != "in":
        return out
    old = dict(before["sides"])
    sport = g.get("sport", "")
    for team, sc in sides:
        prev = old.get(team)
        if prev is None or prev == sc:
            continue
        if sport == "CRICKET":
            w0, w1 = wickets(prev), wickets(sc)
            if w0 is not None and w1 is not None and w1 > w0:
                out.append(("score", f"Wicket! {g['title']}", f"{team} {sc}"))
        elif sport in ("FOOTBALL", "HOCKEY", "RUGBY"):
            out.append(("score", f"Score: {g['title']}", score_line(sides)))
    if sport not in ("CRICKET", "FOOTBALL", "HOCKEY", "RUGBY") and len(sides) == 2:
        # Other sports score all the time: only a change of leader.
        def leader(pairs):
            r = [(runs(s) or 0) for _, s in pairs]
            return None if r[0] == r[1] else (0 if r[0] > r[1] else 1)
        l0, l1 = leader(before["sides"]), leader(sides)
        if l1 is not None and l0 is not None and l1 != l0:
            out.append(("score", f"Lead change: {g['title']}", score_line(sides)))
    return out


def watch_games():
    """Every minute: the followed games about to start or on are read again, and changes pushed to the phones."""
    now = time.time()
    games = {}
    for d in devices.values():
        games.update(d["games"])
    for mid, g in games.items():
        start = g.get("start", 0) / 1000
        if not (start - 600 <= now <= start + 12 * 3600) or watched.get(mid, {}).get("state") == "post":
            continue
        try:
            state, sides = game_now(g)
        except Exception:
            continue
        before = watched.get(mid)
        for kind, title, body in what_changed(g, before, state, sides):
            for token, d in list(devices.items()):
                if mid in d["games"] and d.get(kind):
                    fcm_send(token, title, body, f"{kind}:{mid}")
        watched[mid] = {"state": state, "sides": sides}
    # Games long over are forgotten.
    for mid in [m for m, w in watched.items() if m not in games]:
        watched.pop(mid, None)


def watcher():
    while True:
        time.sleep(60)
        try:
            watch_games()
        except Exception:
            log("watcher: " + traceback.format_exc())


# ---------------------------------------------------------------- schedule

def scheduler():
    threading.Thread(target=build_addons, daemon=True).start()
    build_guide()
    last_addons, last_guide_slot = time.time(), None
    while True:
        time.sleep(30)
        if time.time() - last_addons >= 600:
            last_addons = time.time()
            threading.Thread(target=build_addons, daemon=True).start()
        now = datetime.now(TZ)
        slot = (now.date(), now.hour)
        if now.hour in GUIDE_HOURS and now.minute < 5 and slot != last_guide_slot:
            last_guide_slot = slot
            threading.Thread(target=build_guide, daemon=True).start()


# ---------------------------------------------------------------- web

api = FastAPI()


def check(key):
    if not KEY or key != KEY:
        raise HTTPException(404)


def ago(t):
    return None if t is None else f"{int(time.time() - t)}s ago"


@api.get("/k/{key}/status")
def status(key: str):
    check(key)
    s = json.loads(json.dumps(state))
    for part in ("playlist", "guide", "addons"):
        s[part]["at"] = ago(state[part]["at"])
    s["up"] = f"{int(time.time() - state['started'])}s"
    s["alerts"] = {"phones": len(devices), "games_followed": len({m for d in devices.values() for m in d["games"]}),
                   "games_on_now": sum(1 for w in watched.values() if w["state"] == "in"), "sent": alerts_sent["count"],
                   "last": alerts_sent["last"], "error": alerts_sent["error"],
                   "firebase_key_set": bool((os.environ.get("FIREBASE_SA") or os.environ.get("FIREBASE")))}
    return JSONResponse(s)


@api.get("/k/{key}/refresh")
def refresh(key: str, what: str = "all"):
    check(key)
    if what in ("all", "guide"):
        threading.Thread(target=build_guide, daemon=True).start()
    if what in ("all", "addons"):
        threading.Thread(target=build_addons, daemon=True).start()
    return {"started": what}


@api.get("/k/{key}/crosscheck")
def crosscheck(key: str, start: int = 0):
    check(key)
    if start and not cross["running"]:
        threading.Thread(target=run_crosscheck, daemon=True).start()
        return {"started": True}
    return {"running": cross["running"], "at": ago(cross["at"]), "error": cross["error"], "report": cross["report"]}


@api.post("/k/{key}/watch")
async def watch(key: str, request: Request):
    """A phone's push address, the games it follows, and which alerts it wants (sent whenever its Sports refreshes)."""
    check(key)
    b = await request.json()
    token = (b.get("token") or "").strip()
    if not token:
        raise HTTPException(400, "no token")
    devices[token] = {"score": bool(b.get("score", True)), "end": bool(b.get("end", True)), "at": time.time(),
                      "games": {g["match"]: g for g in (b.get("games") or [])[:100] if g.get("match") and g.get("path")}}
    return {"watching": len(devices[token]["games"])}


@api.get("/k/{key}/guide.xml.gz")
def guide(key: str):
    check(key)
    path = f"{DATA}/guide.xml.gz"
    if not os.path.exists(path):
        raise HTTPException(503, "The guide is still being built: try again in a few minutes.")
    return FileResponse(path, media_type="application/gzip", filename="guide.xml.gz")


@api.get("/k/{key}/manifest.json")
def manifest(key: str):
    check(key)
    if not addon_view["manifest"]:
        raise HTTPException(503, "Addons are still being read: try again in a minute.")
    return addon_view["manifest"]


@api.get("/k/{key}/catalog/tv/{cid}.json")
def catalog(key: str, cid: str):
    check(key)
    return {"metas": addon_view["catalogs"].get(urllib.parse.unquote(cid), {}).get("metas", [])}


@api.get("/k/{key}/stream/tv/{sid}.json")
def stream(key: str, sid: str):
    check(key)
    return {"streams": streams_for(urllib.parse.unquote(sid))}


with gr.Blocks() as page:
    gr.Markdown("## NovaPlay server\nRunning. The app talks to it directly; nothing to do here.")

app = gr.mount_gradio_app(api, page, path="/", ssr_mode=False)

if __name__ == "__main__":
    if not KEY:
        log("SERVER_KEY is not set: add it under Settings -> Variables and secrets.")
    threading.Thread(target=scheduler, daemon=True).start()
    threading.Thread(target=watcher, daemon=True).start()
    uvicorn.run(app, host="0.0.0.0", port=7860)
