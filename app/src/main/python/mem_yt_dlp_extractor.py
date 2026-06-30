import json
import os
import time
import traceback
from html.parser import HTMLParser
from urllib.parse import urljoin
from urllib.request import Request, urlopen

import yt_dlp


MAX_TEXT = 1200
MAX_LIST = 30
MAX_HTML_BYTES = 2_000_000
MAX_ARTICLE_TEXT = 12_000
READABLE_TAGS = {"article", "main", "section", "p", "h1", "h2", "h3", "li", "blockquote"}
SKIP_TEXT_TAGS = {"script", "style", "noscript", "svg", "nav", "footer", "form", "button"}


def extract(url, files_dir, ffmpeg_path=""):
    started = time.time()
    cache_dir = os.path.join(files_dir or "", "yt_dlp_cache")
    os.makedirs(cache_dir, exist_ok=True)

    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": False,
        "extract_flat": False,
        "ignore_no_formats_error": True,
        "socket_timeout": 30,
        "cachedir": cache_dir,
    }
    if ffmpeg_path:
        options["ffmpeg_location"] = ffmpeg_path

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
        payload = {
            "ok": True,
            "sourceUrl": url,
            "ytDlpVersion": yt_dlp.version.__version__,
            "durationMs": int((time.time() - started) * 1000),
            "ffmpegDetected": bool(ffmpeg_path),
            "ragCandidate": summarize(info),
        }
    except Exception as exc:
        message = str(exc)
        auth_required = is_auth_required(message)
        if auth_required:
            payload = error_payload(url, started, exc, auth_required)
        else:
            payload = article_fallback_payload(url, started, exc)
    return json.dumps(payload, ensure_ascii=False)


def error_payload(url, started, exc, auth_required=False):
    message = str(exc)
    payload = {
        "ok": False,
        "sourceUrl": url,
        "ytDlpVersion": yt_dlp.version.__version__,
        "durationMs": int((time.time() - started) * 1000),
        "errorType": exc.__class__.__name__,
        "error": message,
        "authRequired": auth_required,
        "nextStep": auth_next_step() if auth_required else None,
        "trace": traceback.format_exc(limit=4),
    }
    if auth_required:
        payload["ragCandidate"] = {
            "type": "needs_auth",
            "title": auth_title(url),
            "descriptionPreview": auth_summary(message),
            "webpageUrl": url,
            "extractor": "auth_gate",
            "extractorKey": "NeedsAuth",
            "ragText": auth_summary(message),
        }
    return payload


def auth_title(url):
    host = url.split("//", 1)[-1].split("/", 1)[0] if url else "source"
    host = host.replace("www.", "")
    return f"Auth required: {host}"


def auth_summary(message):
    return (
        "This source requires an authenticated session before Mem can extract "
        "metadata or content. The URL has been saved for retry once explicit "
        f"source auth is available. Extractor message: {text(message, limit=500)}"
    )


def article_fallback_payload(url, started, yt_dlp_error):
    try:
        page = fetch_article_metadata(url)
        page["ytDlpError"] = str(yt_dlp_error)
        return {
            "ok": True,
            "sourceUrl": url,
            "ytDlpVersion": yt_dlp.version.__version__,
            "durationMs": int((time.time() - started) * 1000),
            "fallback": "article_metadata",
            "ragCandidate": page,
        }
    except Exception as article_error:
        payload = error_payload(url, started, yt_dlp_error, False)
        payload["fallback"] = "article_metadata"
        payload["fallbackErrorType"] = article_error.__class__.__name__
        payload["fallbackError"] = str(article_error)
        return payload


def summarize(info):
    if not isinstance(info, dict):
        return {"type": "unknown", "rawType": type(info).__name__}

    if info.get("_type") in ("playlist", "multi_video") or isinstance(info.get("entries"), list):
        entries = [summarize_item(entry) for entry in (info.get("entries") or [])[:MAX_LIST] if entry]
        return {
            "type": info.get("_type") or "playlist",
            "id": info.get("id"),
            "title": text(info.get("title")),
            "webpageUrl": info.get("webpage_url") or info.get("original_url"),
            "extractor": info.get("extractor"),
            "entryCount": info.get("playlist_count") or len(info.get("entries") or []),
            "entriesPreview": entries,
        }

    return summarize_item(info)


def summarize_item(info):
    if not isinstance(info, dict):
        return {"type": "item", "value": text(str(info))}

    subtitle_langs = sorted((info.get("subtitles") or {}).keys())
    auto_caption_langs = sorted((info.get("automatic_captions") or {}).keys())
    tags = info.get("tags") or []
    categories = info.get("categories") or []
    formats = info.get("formats") or []
    thumbnails = info.get("thumbnails") or []

    return {
        "type": info.get("_type") or "video",
        "id": info.get("id"),
        "title": text(info.get("title")),
        "descriptionPreview": text(info.get("description")),
        "webpageUrl": info.get("webpage_url") or info.get("original_url"),
        "extractor": info.get("extractor"),
        "extractorKey": info.get("extractor_key"),
        "durationSeconds": info.get("duration"),
        "timestamp": info.get("timestamp"),
        "uploadDate": info.get("upload_date"),
        "uploader": text(info.get("uploader")),
        "channel": text(info.get("channel")),
        "language": info.get("language"),
        "availability": info.get("availability"),
        "liveStatus": info.get("live_status"),
        "ageLimit": info.get("age_limit"),
        "viewCount": info.get("view_count"),
        "likeCount": info.get("like_count"),
        "thumbnail": info.get("thumbnail"),
        "thumbnailCount": len(thumbnails),
        "formatCount": len(formats),
        "subtitleLanguages": subtitle_langs[:MAX_LIST],
        "automaticCaptionLanguages": auto_caption_langs[:MAX_LIST],
        "categories": categories[:MAX_LIST],
        "tagsPreview": tags[:MAX_LIST],
        "ragText": rag_text(info, subtitle_langs, auto_caption_langs),
    }


class ArticleMetadataParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title_parts = []
        self.in_title = False
        self.meta = {}
        self.links = {}
        self.skip_depth = 0
        self.readable_depth = 0
        self.current_text_tag = None
        self.current_text = []
        self.readable_blocks = []

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        attrs = {str(key).lower(): value for key, value in attrs if key}
        if tag in SKIP_TEXT_TAGS:
            self.skip_depth += 1
        if tag == "title":
            self.in_title = True
        elif tag == "meta":
            key = attrs.get("property") or attrs.get("name")
            content = attrs.get("content")
            if key and content:
                self.meta[key.lower()] = content.strip()
        elif tag == "link":
            rel = attrs.get("rel")
            href = attrs.get("href")
            if rel and href:
                for part in str(rel).lower().split():
                    self.links[part] = href.strip()
        if tag in READABLE_TAGS:
            self.readable_depth += 1
            if tag not in ("article", "main", "section"):
                self._flush_current_text()
                self.current_text_tag = tag

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag == "title":
            self.in_title = False
        if tag in SKIP_TEXT_TAGS and self.skip_depth:
            self.skip_depth -= 1
        if tag == self.current_text_tag:
            self._flush_current_text()
        if tag in READABLE_TAGS and self.readable_depth:
            self.readable_depth -= 1

    def handle_data(self, data):
        if self.in_title and data:
            self.title_parts.append(data.strip())
        if self.skip_depth == 0 and self.readable_depth > 0 and self.current_text_tag and data:
            self.current_text.append(data)

    @property
    def title(self):
        return text(" ".join(part for part in self.title_parts if part))

    @property
    def readable_text(self):
        self._flush_current_text()
        deduped = []
        seen = set()
        total = 0
        for block in self.readable_blocks:
            normalized = normalize_space(block)
            if len(normalized) < 40:
                continue
            key = normalized.lower()
            if key in seen:
                continue
            seen.add(key)
            deduped.append(normalized)
            total += len(normalized)
            if total >= MAX_ARTICLE_TEXT:
                break
        return text("\n\n".join(deduped), limit=MAX_ARTICLE_TEXT)

    def _flush_current_text(self):
        if not self.current_text:
            self.current_text_tag = None
            return
        block = normalize_space(" ".join(self.current_text))
        if block:
            self.readable_blocks.append(block)
        self.current_text = []
        self.current_text_tag = None


def fetch_article_metadata(url):
    request = Request(
        url,
        headers={
            "User-Agent": "Mem/0.3 article metadata extractor",
            "Accept": "text/html,application/xhtml+xml",
        },
    )
    with urlopen(request, timeout=30) as response:
        final_url = response.geturl() or url
        content_type = response.headers.get("content-type", "")
        if "html" not in content_type.lower():
            raise ValueError("Article fallback only supports HTML pages")
        raw = response.read(MAX_HTML_BYTES + 1)
        if len(raw) > MAX_HTML_BYTES:
            raw = raw[:MAX_HTML_BYTES]
        charset = response.headers.get_content_charset() or "utf-8"

    html = raw.decode(charset, errors="replace")
    parser = ArticleMetadataParser()
    parser.feed(html)
    readable_text = parser.readable_text

    title = first_text(
        parser.meta.get("og:title"),
        parser.meta.get("twitter:title"),
        parser.title,
        final_url,
    )
    description = first_text(
        parser.meta.get("og:description"),
        parser.meta.get("twitter:description"),
        parser.meta.get("description"),
        readable_text,
    )
    author = first_text(
        parser.meta.get("author"),
        parser.meta.get("article:author"),
        parser.meta.get("parsely-author"),
    )
    canonical_url = first_text(
        parser.meta.get("og:url"),
        parser.links.get("canonical"),
        final_url,
    )
    thumbnail = first_text(
        parser.meta.get("og:image"),
        parser.meta.get("twitter:image"),
        parser.meta.get("twitter:image:src"),
    )
    if canonical_url:
        canonical_url = urljoin(final_url, canonical_url)
    if thumbnail:
        thumbnail = urljoin(final_url, thumbnail)

    return {
        "type": "article",
        "title": title,
        "descriptionPreview": description,
        "webpageUrl": canonical_url or final_url,
        "extractor": "article_metadata",
        "extractorKey": "ArticleMetadata",
        "uploader": author,
        "channel": None,
        "thumbnail": thumbnail,
        "contentText": readable_text,
        "contentTextLength": len(readable_text or ""),
        "language": first_text(
            parser.meta.get("og:locale"),
            parser.meta.get("language"),
        ),
        "ragText": article_rag_text(title, author, description, readable_text, canonical_url or final_url),
    }


def first_text(*values):
    for value in values:
        cleaned = text(value)
        if cleaned:
            return cleaned
    return None


def article_rag_text(title, author, description, readable_text, url):
    parts = [title, author, description, readable_text, url]
    return text("\n\n".join(part for part in parts if part), limit=2500)


def rag_text(info, subtitle_langs, auto_caption_langs):
    parts = [
        info.get("title"),
        info.get("uploader"),
        info.get("channel"),
        info.get("description"),
    ]
    if subtitle_langs:
        parts.append("Subtitle languages: " + ", ".join(subtitle_langs[:MAX_LIST]))
    if auto_caption_langs:
        parts.append("Automatic caption languages: " + ", ".join(auto_caption_langs[:MAX_LIST]))
    return text("\n\n".join(str(part) for part in parts if part), limit=2500)


def text(value, limit=MAX_TEXT):
    if value is None:
        return None
    value = str(value).replace("\r\n", "\n").replace("\r", "\n").strip()
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."


def normalize_space(value):
    return " ".join(str(value).split())


def is_auth_required(message):
    lower = (message or "").lower()
    needles = [
        "cookies",
        "logged-in",
        "login",
        "sign in",
        "empty media response",
        "private",
        "not accessible",
    ]
    return any(needle in lower for needle in needles)


def auth_next_step():
    return (
        "This source appears to require an authenticated browser session. "
        "The spike only supports logged-out extraction. Copy/share this log, "
        "and use a public/owned URL for now; a later spike can evaluate explicit "
        "user-imported cookies."
    )
