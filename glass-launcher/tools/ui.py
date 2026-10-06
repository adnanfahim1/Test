#!/usr/bin/env python3
"""Tiny UI driver for the emulator check (uses adb + uiautomator).

  ui.py has  REGEX            exit 0 when a view's text matches
  ui.py tap  REGEX [--scroll] tap the first matching view (scrolls down to find it)
  ui.py wait REGEX SECONDS    wait until a view's text matches
  ui.py text                  print all visible texts
"""
import re, subprocess, sys, time

def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True, errors="replace").stdout

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = (m.group(1).replace("&amp;", "&").replace("&quot;", '"').replace("&#10;", "\n")
             .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'"))
        x1, y1, x2, y2 = map(int, m.group(2, 3, 4, 5))
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return out

def find(rx):
    r = re.compile(rx, re.S)
    for t, x, y in nodes():
        if t and r.search(t):
            return t, x, y
    return None

cmd = sys.argv[1]
if cmd == "text":
    for t, _, _ in nodes():
        if t: print(t.replace("\n", " | "))
elif cmd == "edit":
    # Tap the first text field, then type the text.
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    m = re.search(r'class="android.widget.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        print("no text field"); sys.exit(1)
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.5)
    adb("shell", "input", "text", sys.argv[2])
elif cmd == "has":
    sys.exit(0 if find(sys.argv[2]) else 1)
elif cmd == "wait":
    end = time.time() + float(sys.argv[3])
    while time.time() < end:
        if find(sys.argv[2]):
            sys.exit(0)
        time.sleep(2)
    sys.exit(1)
elif cmd == "tap":
    for i in range(7):
        f = find(sys.argv[2])
        if f:
            adb("shell", "input", "tap", str(f[1]), str(f[2]))
            print("tapped:", f[0].replace("\n", " ")[:60])
            sys.exit(0)
        if "--scroll" not in sys.argv:
            break
        adb("shell", "input", "swipe", "640", "620", "640", "200", "250")
        time.sleep(0.8)
    print("not found:", sys.argv[2])
    sys.exit(1)
