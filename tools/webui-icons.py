#!/usr/bin/env python3
"""Regenerate the app's icon asset catalog from nanobot's own WebUI sources.

The companion does not invent its own glyphs: it uses the ones the WebUI ships, so a
row, a chip and a button read the same on the web and on the phone. Two families:

  - `webui/src/components/icons/product-icons.tsx` — nanobot's own 24x24 drawings,
    stroke 1.75, round caps. New chat, send, voice, stop, settings, archive, ...
  - `lucide-react` icons the WebUI imports by name (Menu, X, ChevronDown, ...),
    stroke 2. Those are the same glyphs the browser draws.

Run it with the gateway checkout present (the read-only source of record):

    python3 tools/webui-icons.py

It writes `iosApp/iosApp/Assets.xcassets/Icon*.imageset` as template images, so a
SwiftUI view tints them with `foregroundStyle` exactly like an SF Symbol.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

GATEWAY = Path.home() / "Dev" / "nanobot" / "webui"
CATALOG = Path(__file__).resolve().parent.parent / "iosApp" / "iosApp" / "Assets.xcassets"

# The glyphs the app uses, by the name it asks for in Swift.
PRODUCT_ICONS = {
    "NewChat": "NewChatIcon",
    "Search": "SearchIcon",
    "Settings": "SettingsIcon",
    "Archive": "ArchiveIcon",
    "Automations": "AutomationsIcon",
    "Attach": "AttachIcon",
    "Send": "SendIcon",
    "Voice": "VoiceIcon",
    "Stop": "StopIcon",
    "History": "HistoryIcon",
    "ErrorTask": "ErrorTaskIcon",
    "FullAccess": "FullAccessIcon",
    "Restricted": "RestrictedAccessIcon",
    "Conversation": "ConversationIcon",
    "Overview": "OverviewIcon",
    "Appearance": "AppearanceIcon",
    "Models": "ModelsIcon",
    "Compact": "CompactIcon",
    "Activity": "ActivityIcon",
    "Status": "StatusIcon",
}

# Named exactly as the WebUI imports them, aliases included (`Globe2`, `AlertTriangle`),
# so a reader can grep the same name in `webui/src` and here.
LUCIDE_ICONS = {
    "Menu": "Menu",
    "Close": "X",
    "Check": "Check",
    "ChevronDown": "ChevronDown",
    "ChevronRight": "ChevronRight",
    "Copy": "Copy",
    "Globe": "Globe2",
    "Image": "FileImage",
    "Video": "FileVideo2",
    "Warning": "AlertTriangle",
    "Trash": "Trash2",
    "Quote": "Quote",
    "Clock": "Clock",
    "Terminal": "Terminal",
    "Server": "Server",
    "Key": "Key",
}

# JSX attribute name -> SVG attribute name. `key` is React bookkeeping and is dropped.
ATTRIBUTE_NAMES = {
    "fillRule": "fill-rule",
    "clipRule": "clip-rule",
    "strokeWidth": "stroke-width",
    "strokeLinecap": "stroke-linecap",
    "strokeLinejoin": "stroke-linejoin",
    "strokeLinecap ": "stroke-linecap",
    "vectorEffect": "vector-effect",
    "stopColor": "stop-color",
    "stopOpacity": "stop-opacity",
    "fillOpacity": "fill-opacity",
    "strokeOpacity": "stroke-opacity",
}

TAG = re.compile(r"<(?P<tag>path|circle|rect|line|polyline|polygon|ellipse|g)\b(?P<attrs>[^>]*?)(?:/>|>(?P<inner>.*?)</(?P=tag)>)", re.S)
ATTR = re.compile(r'([A-Za-z-]+)\s*=\s*"([^"]*)"')


def convert_jsx(drawing: str) -> str:
    """JSX → SVG children. Expressions (`{...}`) are not used by these drawings."""
    out = []
    for match in TAG.finditer(drawing):
        tag, attrs, inner = match.group("tag"), match.group("attrs"), match.group("inner") or ""
        if "{" in attrs or "{" in inner:
            raise SystemExit(f"unexpected JS expression in icon drawing: {match.group(0)[:80]}")
        svg_attrs = []
        for name, value in ATTR.findall(attrs):
            name = ATTRIBUTE_NAMES.get(name, name)
            if name in ("key", "className"):
                continue
            value = value.replace("currentColor", "currentColor")
            svg_attrs.append(f'{name}="{value}"')
        head = f"<{tag} {' '.join(svg_attrs)}".rstrip()
        out.append(f"{head}/>" if not inner.strip() else f"{head}>{convert_jsx(inner)}</{tag}>")
    return "".join(out)


def svg(children: str, stroke_width: str) -> str:
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"'
        f' fill="none" stroke="currentColor" stroke-width="{stroke_width}"'
        ' stroke-linecap="round" stroke-linejoin="round">'
        f"{children}</svg>\n"
    )


def product_drawings() -> dict[str, str]:
    source = (GATEWAY / "src/components/icons/product-icons.tsx").read_text()
    drawings = {}
    for name in set(PRODUCT_ICONS.values()):
        start = source.find(f"export const {name} = productIcon(")
        if start < 0:
            raise SystemExit(f"product icon {name} not found in {GATEWAY}")
        open_index = source.index(",", start) + 1          # after productIcon("name"
        depth, index = 0, open_index
        while index < len(source):                          # find the matching `);`
            char = source[index]
            if char in "<(":
                depth += 1
            elif char in ">)":
                depth -= 1
                if depth < 0:
                    break
            index += 1
        drawings[name] = source[open_index:index]
    return drawings


def lucide_index() -> dict[str, str]:
    """`Globe2` -> `globe-2`: lucide's own export index, aliases included."""
    index = {}
    source = (GATEWAY / "node_modules/lucide-react/dist/esm/lucide-react.js").read_text()
    for line in source.splitlines():
        match = re.search(r"from '\./icons/([\w-]+)\.js'", line)
        if not match:
            continue
        for exported in re.findall(r"default as (\w+)", line):
            index[exported] = match.group(1)
    return index


def lucide_drawing(name: str, stroke_width: str) -> str:
    file_name = lucide_index().get(name)
    if file_name is None:
        raise SystemExit(f"lucide icon {name} is not exported by the installed lucide-react")
    path = GATEWAY / "node_modules/lucide-react/dist/esm/icons" / f"{file_name}.js"
    if not path.exists():
        raise SystemExit(f"lucide icon {name} not found at {path}")
    body = path.read_text()
    start = body.index("[", body.index("createLucideIcon("))
    end = body.rindex("]")
    node = body[start:end + 1]
    children = []
    for element, attrs in re.findall(r'\["(\w+)",\s*\{(.*?)\}\]', node, re.S):
        pairs = re.findall(r'(\w+):\s*"([^"]*)"', attrs)
        rendered = " ".join(f'{k}="{v}"' for k, v in pairs if k != "key")
        children.append(f"<{element} {rendered}/>")
    return svg("".join(children), stroke_width)


def write_imageset(name: str, contents: str) -> None:
    folder = CATALOG / f"Icon{name}.imageset"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"Icon{name}.svg").write_text(contents)
    (folder / "Contents.json").write_text(json.dumps({
        "images": [{"filename": f"Icon{name}.svg", "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
        "properties": {
            "preserves-vector-representation": True,
            "template-rendering-intent": "template",
        },
    }, indent=2) + "\n")


def main() -> int:
    if not GATEWAY.exists():
        print(f"gateway WebUI not found at {GATEWAY}", file=sys.stderr)
        return 1
    CATALOG.mkdir(parents=True, exist_ok=True)
    (CATALOG / "Contents.json").write_text(json.dumps(
        {"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")

    product = product_drawings()
    for want, source_name in PRODUCT_ICONS.items():
        write_imageset(want, svg(convert_jsx(product[source_name]), "1.75"))
    for want, source_name in LUCIDE_ICONS.items():
        write_imageset(want, lucide_drawing(source_name, "2"))

    print(f"{len(PRODUCT_ICONS)} product icons + {len(LUCIDE_ICONS)} lucide icons → {CATALOG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
