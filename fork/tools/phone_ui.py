#!/usr/bin/env python3
"""Read what is on the phone's screen over adb, or find one element by its label. Read-only.

    fork/tools/phone_ui.py                  # list the labelled elements on screen
    fork/tools/phone_ui.py "Export backup"  # print "x y 'label'" for elements matching the text

Only this app's screens, the system file picker and the permission dialog are listed. Anything else in
front (the home screen, another app) is reported as such and not printed, because it is the owner's
private screen. Pass --app NAME to list one other app's elements instead (its package must contain NAME),
or --any-app to lift the limit altogether. Use either only when the owner has asked for that screen to be read.

Set ANDROID_SERIAL to the phone when an emulator is attached too. The phone must be unlocked and awake.
Set APP_PACKAGE to read a build installed under another package name than this code's.
"""
import html
import os
import re
import subprocess
import sys

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
APP = os.environ.get("APP_PACKAGE", "com.lhoop.whoop")
ALLOWED = (APP, "documentsui", "permissioncontroller")


def dump():
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    return subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout


def nodes(xml):
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        get = lambda k: html.unescape((re.search(k + r'="([^"]*)"', n) or [None, ""])[1])
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        out.append({"text": get("text"), "desc": get("content-desc"), "pkg": get("package"),
                    "click": get("clickable") == "true", "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    return out


def main():
    argv = sys.argv[1:]
    only = None
    if "--app" in argv:
        i = argv.index("--app")
        only = argv[i + 1]
        del argv[i:i + 2]
    args = [a for a in argv if a != "--any-app"]
    found = nodes(dump())
    if only is not None:
        found = [n for n in found if only in n["pkg"]]
        if not found:
            print(f"(no app matching '{only}' is in front)")
            return
    elif "--any-app" not in argv:
        found = [n for n in found if any(a in n["pkg"] for a in ALLOWED)]
        if not found:
            print("(another app or the home screen is in front)")
            return
    if args:
        want = args[0].lower()
        label = lambda n: n["text"] or n["desc"]
        hits = [n for n in found if want == label(n).lower()] or [n for n in found if want in label(n).lower()]
        for n in hits[:5]:
            print(n["cx"], n["cy"], repr(label(n)))
    else:
        for n in found:
            label = n["text"] or n["desc"]
            if label:
                print(f'{n["cx"]:5} {n["cy"]:5} {"*" if n["click"] else " "} {label[:90]!r}')


if __name__ == "__main__":
    main()
