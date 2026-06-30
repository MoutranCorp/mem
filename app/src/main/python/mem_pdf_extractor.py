import json
import time
import traceback

from pypdf import PdfReader


MAX_PAGES = 80
MAX_TEXT = 20000
SUMMARY_TEXT = 1200


def extract(path, display_name="Imported PDF"):
    started = time.time()
    try:
        reader = PdfReader(path)
        metadata = reader.metadata or {}
        if reader.is_encrypted:
            try:
                reader.decrypt("")
            except Exception:
                pass

        page_count = len(reader.pages)
        parts = []
        for index, page in enumerate(reader.pages[:MAX_PAGES]):
            page_text = page.extract_text() or ""
            page_text = normalize_space(page_text)
            if page_text:
                parts.append(f"Page {index + 1}\n{page_text}")
            if sum(len(part) for part in parts) >= MAX_TEXT:
                break

        full_text = truncate("\n\n".join(parts), MAX_TEXT)
        title = clean_meta(metadata.get("/Title")) or display_name or "Imported PDF"
        author = clean_meta(metadata.get("/Author"))
        description = truncate(full_text, SUMMARY_TEXT) if full_text else "PDF imported. No embedded text was extracted."

        payload = {
            "ok": bool(full_text),
            "sourceType": "pdf",
            "extractor": "pypdf",
            "title": title,
            "author": author,
            "pageCount": page_count,
            "pagesRead": min(page_count, MAX_PAGES),
            "textLength": len(full_text or ""),
            "descriptionPreview": description,
            "ragText": full_text,
            "durationMs": int((time.time() - started) * 1000),
        }
    except Exception as exc:
        payload = {
            "ok": False,
            "sourceType": "pdf",
            "extractor": "pypdf",
            "title": display_name or "Imported PDF",
            "errorType": exc.__class__.__name__,
            "error": str(exc),
            "trace": traceback.format_exc(limit=4),
            "durationMs": int((time.time() - started) * 1000),
        }
    return json.dumps(payload, ensure_ascii=False)


def clean_meta(value):
    if value is None:
        return None
    value = str(value).strip()
    return value or None


def normalize_space(value):
    return "\n".join(" ".join(line.split()) for line in str(value).splitlines()).strip()


def truncate(value, limit):
    if value is None:
        return None
    value = str(value).strip()
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."
