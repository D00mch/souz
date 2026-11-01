"""Bounded, offline HTML-to-PDF conversion inside the existing user sandbox."""
import base64
import io
import json
import pathlib
import resource
import sys


def render(source, target):
    source = pathlib.Path(source).resolve(strict=True)
    target = pathlib.Path(target)
    if source.stat().st_size > 500_000:
        raise ValueError("HTML exceeds 500000 bytes")
    if target.exists() or target.is_symlink():
        raise ValueError("Output must be a new file")
    resource.setrlimit(resource.RLIMIT_CPU, (30, 30))
    resource.setrlimit(resource.RLIMIT_AS, (1024 ** 3, 1024 ** 3))
    resource.setrlimit(resource.RLIMIT_FSIZE, (20_000_000, 20_000_000))
    from weasyprint import HTML
    from weasyprint.urls import URLFetcher, URLFetcherResponse

    blocked = []

    class InlineImages(URLFetcher):
        def fetch(self, url, headers=None):
            allowed = ("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,")
            if not url.startswith(allowed) or len(url) > 3_000_000:
                blocked.append(True)
                raise ValueError("Only bounded inline raster images are allowed")
            header, encoded = url.split(",", 1)
            return URLFetcherResponse(
                url, base64.b64decode(encoded, validate=True),
                {"Content-Type": header[5:].split(";")[0]},
            )

    document = HTML(string=source.read_text(encoding="utf-8"), url_fetcher=InlineImages()).render()
    if blocked:
        raise ValueError("HTML references a forbidden resource")
    if len(document.pages) > 500:
        raise ValueError("PDF exceeds 500 pages")
    output = io.BytesIO()
    document.write_pdf(output)
    data = output.getvalue()
    if len(data) > 20_000_000:
        raise ValueError("PDF exceeds 20 MB")
    with target.open("xb") as file:
        file.write(data)
    return {"path": str(target.resolve()), "pages": len(document.pages), "sizeBytes": len(data)}


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: render_pdf.py source.html new-output.pdf")
        print(json.dumps(render(*sys.argv[1:])))
    except Exception as error:
        print(json.dumps({"error": type(error).__name__, "message": str(error)}))
        sys.exit(1)
