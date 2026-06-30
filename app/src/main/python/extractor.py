import json
import os
import time
import traceback

import yt_dlp


MAX_TEXT = 1200
MAX_LIST = 30


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
        payload = {
            "ok": False,
            "sourceUrl": url,
            "ytDlpVersion": yt_dlp.version.__version__,
            "durationMs": int((time.time() - started) * 1000),
            "errorType": exc.__class__.__name__,
            "error": str(exc),
            "trace": traceback.format_exc(limit=4),
        }
    return json.dumps(payload, ensure_ascii=False)


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
    return value[: limit - 1] + "…"
