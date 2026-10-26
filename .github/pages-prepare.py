"""Turns site/ into a GitHub Pages project site. Used by pages.yml.

site/ is Star Nav's web page as it is served from a domain root (starnav.apachi.tools, Cloudflare). On GitHub Pages
it lives under /<repo>/, and Pages cannot send response headers. So the copy for Pages:
- gets the base path before every root-absolute link (HTML, JS paths, the web manifest);
- drops Cloudflare's _headers file and carries its Content-Security-Policy (frame-ancestors aside, which a <meta>
  cannot set) and referrer policy as <meta> tags;
- names no Cloudflare in its privacy line: there is no Cloudflare in front of GitHub Pages.
Every replacement must match, so a change in site/ that breaks one fails the deploy instead of shipping a broken page.

Usage: python3 -I .github/pages-prepare.py SRC OUT BASE      (BASE like /star-nav)
"""
import json
import re
import shutil
import sys
from pathlib import Path

PRIVACY = [
    ("Ця сторінка теж нічого не відстежує: без аналітики, сторонніх скриптів і шрифтів. Лише захист Cloudflare від ботів "
     "може ставити свій технічний файл cookie.",
     "Ця сторінка теж нічого не відстежує: без аналітики, файлів cookie, сторонніх скриптів і шрифтів."),
    ("This page tracks nothing either: no analytics, third-party scripts or fonts. Only Cloudflare's bot protection may "
     "set its own technical cookie.",
     "This page tracks nothing either: no analytics, cookies, third-party scripts or fonts."),
]


def sub(text: str, pattern: str, repl, what: str, flags: int = 0) -> str:
    out, n = re.subn(pattern, repl, text, flags=flags)
    if n == 0:
        sys.exit(f"pages-prepare: no match for {what}")
    return out


def main() -> None:
    src, out, base = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3].rstrip("/")
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(src, out)

    headers = (out / "_headers").read_text(encoding="utf-8")
    (out / "_headers").unlink()
    csp = re.search(r"^\s*Content-Security-Policy:\s*(.+)$", headers, re.M).group(1)
    csp = "; ".join(d.strip() for d in csp.split(";") if d.strip() and not d.strip().startswith("frame-ancestors"))
    referrer = re.search(r"^\s*Referrer-Policy:\s*(.+)$", headers, re.M).group(1).strip()
    metas = (f'<meta http-equiv="Content-Security-Policy" content="{csp}" />\n'
             f'    <meta name="referrer" content="{referrer}" />')

    for html in sorted(out.glob("*.html")):
        text = html.read_text(encoding="utf-8")
        text = sub(text, r'\b(href|src)="/(?!/)', rf'\1="{base}/', f"root-absolute links in {html.name}")
        # srcset is a list ("/a-360.webp 360w, /a-720.webp 720w"): every root-absolute URL in it.
        text = re.sub(r'\bsrcset="([^"]*)"', lambda m: 'srcset="' + re.sub(r"(^|,\s*)/(?!/)", rf"\1{base}/", m.group(1)) + '"', text)
        text = sub(text, r'<meta charset="utf-8" />', f'<meta charset="utf-8" />\n    {metas}', f"<meta charset> in {html.name}")
        if html.name == "index.html":
            # The HTML is written in Ukrainian (main.js swaps the words), and wraps the line: any whitespace between words.
            old, new = PRIVACY[0]
            text = sub(text, r"\s+".join(map(re.escape, old.split())), new, f"privacy line in {html.name}")
        html.write_text(text, encoding="utf-8")

    js = out / "main.js"
    text = js.read_text(encoding="utf-8")
    text = sub(text, r"`/(?!/)", f"`{base}/", "root-absolute paths in main.js")
    for old, new in PRIVACY:
        text = sub(text, re.escape(old), new.replace("\\", "\\\\"), "privacy line in main.js")
    js.write_text(text, encoding="utf-8")

    manifest = out / "site.webmanifest"
    m = json.loads(manifest.read_text(encoding="utf-8"))
    m["start_url"] = m["scope"] = f"{base}/"
    for icon in m["icons"]:
        icon["src"] = base + icon["src"]
    manifest.write_text(json.dumps(m, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    keep = rf"(?!/|{re.escape(base[1:])}/)"  # protocol-relative, or already under the base path
    left = [f"{p.name}: {hit}" for p in out.rglob("*") if p.suffix in (".html", ".js", ".css", ".webmanifest")
            for hit in re.findall(rf'(?:href|src)="/{keep}[^"]*"|`/{keep}|url\(/{keep}|"src": "/{keep}'
                                  rf'|srcset="(?:[^"]*,\s*)?/{keep}[^"]*"', p.read_text(encoding="utf-8"))]
    if left:
        sys.exit("pages-prepare: root-absolute paths left: " + ", ".join(left))


if __name__ == "__main__":
    main()
