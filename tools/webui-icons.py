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
    "MoreHorizontal": "MoreHorizontal",
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


ANDROID_DRAWABLES = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"


def android_vector(name: str, drawing: str, stroke_width: str) -> str:
    """The same geometry as an Android VectorDrawable.

    VectorDrawable has no `circle`/`line`/`rect` elements, so they are expressed as paths; a
    stroke-only path with a transparent fill is what a tinted Compose `Icon` wants.
    """
    import math

    paths = []
    for element, attrs in re.findall(r"<(path|circle|rect|line)([^>]*)/>", drawing):
        values = dict(re.findall(r'(\w+[-\w]*)\s*=\s*"([^"]*)"', attrs))
        attributes = [
            'android:strokeWidth="%s"' % values.get("stroke-width", stroke_width),
            'android:strokeColor="#FFFFFFFF"',
            'android:strokeLineCap="round"',
            'android:strokeLineJoin="round"',
            'android:fillColor="#00000000"',
        ]
        if element == "path":
            path_data = values["d"]
        elif element == "line":
            path_data = f'M{values["x1"]},{values["y1"]} L{values["x2"]},{values["y2"]}'
        elif element == "circle":
            cx, cy, r = float(values["cx"]), float(values["cy"]), float(values["r"])
            path_data = (f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 "
                         f"a{r},{r} 0 1,0 {-2 * r},0")
        else:  # rect, with or without rounded corners
            x, y = float(values.get("x", 0)), float(values.get("y", 0))
            w, h = float(values["width"]), float(values["height"])
            r = float(values.get("rx", 0))
            if r:
                path_data = (f"M{x + r},{y} h{w - 2 * r} a{r},{r} 0 0,1 {r},{r} "
                             f"v{h - 2 * r} a{r},{r} 0 0,1 {-r},{r} h{-(w - 2 * r)} "
                             f"a{r},{r} 0 0,1 {-r},{-r} v{-(h - 2 * r)} a{r},{r} 0 0,1 {r},{-r} z")
            else:
                path_data = f"M{x},{y} h{w} v{h} h{-w} z"
        rendered = "\n        ".join(attributes + [f'android:pathData="{path_data}"'])
        paths.append(f"    <path\n        {rendered} />")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated by tools/webui-icons.py from nanobot\'s WebUI source. Do not edit. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
        + "\n".join(paths) + "\n</vector>\n"
    )


def write_drawable(name: str, contents: str) -> None:
    ANDROID_DRAWABLES.mkdir(parents=True, exist_ok=True)
    file_name = "ic_webui_" + re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower() + ".xml"
    (ANDROID_DRAWABLES / file_name).write_text(contents)


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
    android = {}
    for want, source_name in PRODUCT_ICONS.items():
        drawing = convert_jsx(product[source_name])
        write_imageset(want, svg(drawing, "1.75"))
        android[want] = android_vector(want, drawing, "1.75")
    for want, source_name in LUCIDE_ICONS.items():
        contents = lucide_drawing(source_name, "2")
        write_imageset(want, contents)
        android[want] = android_vector(want, contents, "2")
    for want, contents in android.items():
        write_drawable(want, contents)

    print(f"{len(PRODUCT_ICONS)} product icons + {len(LUCIDE_ICONS)} lucide icons → "
          f"{CATALOG} and {ANDROID_DRAWABLES}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
