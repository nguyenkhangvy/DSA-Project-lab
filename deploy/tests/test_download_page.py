"""The download page (pages/, published by .github/workflows/pages.yml; spec 2026-10-02-easy-install-design.md, 2):
both languages say the same things and download the newest release, and the page holds nothing it can't show."""

from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PAGES = ROOT / "pages"
DOWNLOAD = "https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe"


class Reader(HTMLParser):
    """Per language (each <main data-lang>): whether it starts hidden, its download links, how many steps and
    questions it has. And every file the page uses: its stylesheet and its images."""

    def __init__(self):
        super().__init__()
        self.lang = None
        self.in_steps = False
        self.sections = {}
        self.files = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "main" and "data-lang" in attrs:
            self.lang = attrs["data-lang"]
            self.sections[self.lang] = {"hidden": "hidden" in attrs, "downloads": [], "steps": 0, "questions": 0}
        section = self.sections.get(self.lang)
        if tag == "img":
            self.files.append(attrs.get("src", ""))
        if tag == "link" and attrs.get("rel") == "stylesheet":
            self.files.append(attrs.get("href", ""))
        if tag == "ol" and "steps" in (attrs.get("class") or "").split():
            self.in_steps = True
        if section is None:
            return
        if tag == "a" and "download" in (attrs.get("class") or "").split():
            section["downloads"].append(attrs.get("href"))
        if tag == "li" and self.in_steps:
            section["steps"] += 1
        if tag == "dt":
            section["questions"] += 1

    def handle_endtag(self, tag):
        if tag == "main":
            self.lang = None
        if tag == "ol":
            self.in_steps = False


def read():
    reader = Reader()
    reader.feed((PAGES / "index.html").read_text(encoding="utf-8"))
    return reader


def test_the_page_is_in_english_and_vietnamese_and_shows_english_without_javascript():
    sections = read().sections

    assert set(sections) == {"en", "vi"}
    assert not sections["en"]["hidden"] and sections["vi"]["hidden"]


def test_both_languages_download_the_newest_release_and_say_the_same_things():
    for lang, section in read().sections.items():
        assert section["downloads"] == [DOWNLOAD], lang
        assert section["steps"] == 3, lang
        assert section["questions"] == 3, lang


def test_every_file_the_page_uses_is_there():
    files = read().files

    assert "style.css" in files
    for source in files:
        assert (PAGES / source).is_file(), source
