import json
import os
import re
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
MAX_TRANSCRIPT_BYTES = 1_500_000
MAX_TRANSCRIPT_SEGMENTS = 1200
TRANSCRIPT_CHUNK_TARGET_MS = 60_000
TRANSCRIPT_CHUNK_MAX_MS = 90_000
READABLE_TAGS = {"article", "main", "section", "p", "h1", "h2", "h3", "li", "blockquote"}
SKIP_TEXT_TAGS = {"script", "style", "noscript", "svg", "nav", "footer", "form", "button"}


def extract(url, files_dir, ffmpeg_path="", cookie_file_path=""):
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
    if cookie_file_path:
        options["cookiefile"] = cookie_file_path

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
        payload = {
            "ok": True,
            "sourceUrl": url,
            "ytDlpVersion": yt_dlp.version.__version__,
            "durationMs": int((time.time() - started) * 1000),
            "ffmpegDetected": bool(ffmpeg_path),
            "cookieFileDetected": bool(cookie_file_path),
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


def download_authorized(url, files_dir, ffmpeg_path="", cookie_file_path=""):
    started = time.time()
    cache_dir = os.path.join(files_dir or "", "yt_dlp_cache")
    output_dir = os.path.join(files_dir or "", "mem-downloads")
    os.makedirs(cache_dir, exist_ok=True)
    os.makedirs(output_dir, exist_ok=True)

    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "extract_flat": False,
        "ignore_no_formats_error": False,
        "socket_timeout": 30,
        "cachedir": cache_dir,
        "paths": {"home": output_dir, "temp": output_dir},
        "outtmpl": "%(extractor_key)s-%(id)s.%(ext)s",
        "restrictfilenames": True,
        "format": "best[ext=mp4]/best",
    }
    if ffmpeg_path:
        options["ffmpeg_location"] = ffmpeg_path
    if cookie_file_path:
        options["cookiefile"] = cookie_file_path

    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
            prepared = ydl.prepare_filename(info)
        local_path = resolved_download_path(prepared, output_dir, started)
        if not local_path:
            raise FileNotFoundError("yt-dlp finished but no downloaded media file was found")
        payload = {
            "ok": True,
            "sourceUrl": url,
            "ytDlpVersion": yt_dlp.version.__version__,
            "durationMs": int((time.time() - started) * 1000),
            "ffmpegDetected": bool(ffmpeg_path),
            "cookieFileDetected": bool(cookie_file_path),
            "localPath": local_path,
            "mimeType": mime_type_for(local_path),
            "title": text(info.get("title")) if isinstance(info, dict) else None,
            "durationSeconds": info.get("duration") if isinstance(info, dict) else None,
            "webpageUrl": info.get("webpage_url") if isinstance(info, dict) else url,
        }
    except Exception as exc:
        payload = error_payload(url, started, exc, is_auth_required(str(exc)))
    return json.dumps(payload, ensure_ascii=False)


def resolved_download_path(prepared, output_dir, started):
    candidates = []
    if prepared and os.path.exists(prepared):
        candidates.append(prepared)
    try:
        for name in os.listdir(output_dir):
            path = os.path.join(output_dir, name)
            if not os.path.isfile(path):
                continue
            if path.endswith((".part", ".ytdl", ".temp")):
                continue
            if os.path.getmtime(path) >= started - 2:
                candidates.append(path)
    except FileNotFoundError:
        pass
    if not candidates:
        return None
    return max(candidates, key=lambda path: os.path.getmtime(path))


def mime_type_for(path):
    lower = (path or "").lower()
    if lower.endswith(".mp4") or lower.endswith(".m4v"):
        return "video/mp4"
    if lower.endswith(".webm"):
        return "video/webm"
    if lower.endswith(".mov"):
        return "video/quicktime"
    if lower.endswith(".mkv"):
        return "video/x-matroska"
    return "video/*"


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
    transcript = extract_transcript(info)
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
        "chosenTranscriptLanguage": transcript.get("language"),
        "chosenTranscriptSource": transcript.get("source") or "none",
        "transcriptFormat": transcript.get("format"),
        "transcriptSegmentCount": len(transcript.get("segments") or []),
        "transcriptChunkCount": len(transcript.get("chunks") or []),
        "transcriptSegments": transcript.get("segments") or [],
        "transcriptChunks": transcript.get("chunks") or [],
        "categories": categories[:MAX_LIST],
        "tagsPreview": tags[:MAX_LIST],
        "ragText": rag_text(info, subtitle_langs, auto_caption_langs, transcript),
    }


def extract_transcript(info):
    selected = choose_transcript_candidate(info.get("subtitles") or {}, "manual")
    if not selected:
        selected = choose_transcript_candidate(info.get("automatic_captions") or {}, "automatic")
    if not selected:
        return {"source": "none", "language": None, "segments": [], "chunks": []}
    try:
        raw = fetch_text_url(selected["url"], MAX_TRANSCRIPT_BYTES)
        segments = parse_caption_text(raw, selected.get("ext"))
        if not segments:
            return {"source": selected["source"], "language": selected["language"], "segments": [], "chunks": []}
        segments = segments[:MAX_TRANSCRIPT_SEGMENTS]
        chunks = build_transcript_chunks(segments)
        return {
            "source": selected["source"],
            "language": selected["language"],
            "format": selected.get("ext"),
            "segments": segments,
            "chunks": chunks,
        }
    except Exception as exc:
        return {
            "source": selected["source"],
            "language": selected["language"],
            "format": selected.get("ext"),
            "error": str(exc),
            "segments": [],
            "chunks": [],
        }


def choose_transcript_candidate(groups, source):
    preferred_langs = ["en", "en-US", "en-GB"]
    languages = []
    for lang in preferred_langs:
        if lang in groups:
            languages.append(lang)
    languages.extend(lang for lang in sorted(groups.keys()) if lang not in languages and not should_skip_caption_language(lang))
    for language in languages:
        candidates = groups.get(language) or []
        for ext in ("vtt", "srt", "srv3", "ttml", "json3"):
            for item in candidates:
                url = item.get("url") if isinstance(item, dict) else None
                item_ext = (item.get("ext") or "").lower() if isinstance(item, dict) else ""
                if url and (item_ext == ext or (not item_ext and ext in url.lower())):
                    return {"source": source, "language": language, "ext": item_ext or ext, "url": url}
        for item in candidates:
            url = item.get("url") if isinstance(item, dict) else None
            if url:
                return {"source": source, "language": language, "ext": (item.get("ext") or "").lower(), "url": url}
    return None


def should_skip_caption_language(language):
    lower = (language or "").lower()
    return "live_chat" in lower or "danmaku" in lower or lower.startswith("rechat")


def fetch_text_url(url, max_bytes):
    request = Request(url, headers={"User-Agent": "Mem/0.3 transcript extractor"})
    with urlopen(request, timeout=30) as response:
        raw = response.read(max_bytes + 1)
        if len(raw) > max_bytes:
            raw = raw[:max_bytes]
        charset = response.headers.get_content_charset() or "utf-8"
    return raw.decode(charset, errors="replace")


def parse_caption_text(raw, ext):
    raw = raw or ""
    lower_ext = (ext or "").lower()
    if lower_ext == "json3" or raw.lstrip().startswith("{"):
        return parse_json3_caption(raw)
    return parse_timed_text(raw)


def parse_json3_caption(raw):
    data = json.loads(raw)
    segments = []
    for event in data.get("events") or []:
        start = event.get("tStartMs")
        duration = event.get("dDurationMs") or 0
        parts = []
        for seg in event.get("segs") or []:
            value = seg.get("utf8")
            if value:
                parts.append(value)
        text_value = normalize_space("".join(parts))
        if start is not None and text_value:
            segments.append({
                "startMs": int(start),
                "endMs": int(start + duration),
                "text": text_value,
            })
    return merge_duplicate_caption_segments(segments)


def parse_timed_text(raw):
    segments = []
    current_start = None
    current_end = None
    current_text = []
    for line in raw.replace("\ufeff", "").splitlines():
        stripped = line.strip()
        if not stripped:
            flush_caption_segment(segments, current_start, current_end, current_text)
            current_start = None
            current_end = None
            current_text = []
            continue
        if stripped.upper().startswith("WEBVTT") or stripped.startswith("NOTE") or stripped.startswith("STYLE"):
            continue
        if "-->" in stripped:
            flush_caption_segment(segments, current_start, current_end, current_text)
            parts = stripped.split("-->", 1)
            current_start = parse_caption_time(parts[0])
            current_end = parse_caption_time(parts[1].split()[0])
            current_text = []
            continue
        if current_start is None and stripped.isdigit():
            continue
        if current_start is not None:
            current_text.append(strip_caption_markup(stripped))
    flush_caption_segment(segments, current_start, current_end, current_text)
    return merge_duplicate_caption_segments(segments)


def flush_caption_segment(segments, start, end, lines):
    text_value = normalize_space(" ".join(line for line in lines if line))
    if start is not None and end is not None and text_value:
        segments.append({"startMs": int(start), "endMs": int(end), "text": text_value})


def parse_caption_time(value):
    cleaned = value.strip().replace(",", ".")
    match = re.search(r"(?:(\d+):)?(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?", cleaned)
    if not match:
        return 0
    hours = int(match.group(1) or 0)
    minutes = int(match.group(2) or 0)
    seconds = int(match.group(3) or 0)
    millis = int((match.group(4) or "0").ljust(3, "0")[:3])
    return ((hours * 3600 + minutes * 60 + seconds) * 1000) + millis


def strip_caption_markup(value):
    value = re.sub(r"<[^>]+>", " ", value)
    value = value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    return value


def merge_duplicate_caption_segments(segments):
    merged = []
    previous_text = None
    for segment in segments:
        text_value = normalize_space(segment.get("text") or "")
        if not text_value or text_value == previous_text:
            continue
        previous_text = text_value
        merged.append({
            "startMs": int(segment.get("startMs") or 0),
            "endMs": int(segment.get("endMs") or segment.get("startMs") or 0),
            "text": text_value,
        })
    return merged


def build_transcript_chunks(segments):
    chunks = []
    current = []
    current_start = None
    current_end = None
    for segment in segments:
        start = int(segment.get("startMs") or 0)
        end = int(segment.get("endMs") or start)
        if current_start is None:
            current_start = start
        candidate_duration = end - current_start
        if current and candidate_duration > TRANSCRIPT_CHUNK_MAX_MS:
            chunks.append(transcript_chunk(current_start, current_end, current))
            overlap = current[-2:] if len(current) > 2 else current[-1:]
            current = overlap[:]
            current_start = int(current[0].get("startMs") or start) if current else start
        current.append(segment)
        current_end = end
        if current_end - current_start >= TRANSCRIPT_CHUNK_TARGET_MS:
            chunks.append(transcript_chunk(current_start, current_end, current))
            overlap = current[-2:] if len(current) > 2 else current[-1:]
            current = overlap[:]
            current_start = int(current[0].get("startMs") or current_end) if current else None
            current_end = int(current[-1].get("endMs") or current_start) if current else None
    if current:
        chunks.append(transcript_chunk(current_start or 0, current_end or current_start or 0, current))
    return chunks


def transcript_chunk(start, end, segments):
    return {
        "startMs": int(start or 0),
        "endMs": int(end or start or 0),
        "text": normalize_space(" ".join(segment.get("text") or "" for segment in segments)),
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


def rag_text(info, subtitle_langs, auto_caption_langs, transcript=None):
    parts = [
        info.get("title"),
        info.get("uploader"),
        info.get("channel"),
        info.get("description"),
    ]
    transcript = transcript or {}
    transcript_chunks = transcript.get("chunks") or []
    if transcript_chunks:
        parts.append("Transcript:\n" + "\n".join(chunk.get("text", "") for chunk in transcript_chunks[:8]))
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
