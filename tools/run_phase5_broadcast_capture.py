#!/usr/bin/env python3
import subprocess, sys, time, os, re
DEVICE  = "emulator-5554"
PORT    = 19350
OUTPUT  = "tools/v11_phase5_broadcast.flv"
DURATION = 20
TMPDIR  = os.environ.get("LOCALAPPDATA", "C:/Users/benny/AppData/Local") + "/Temp"

def adb(*args):
    return subprocess.run(["adb","-s",DEVICE]+list(args),capture_output=True,text=True,timeout=15)

def ui_bounds(xml):
    if not os.path.exists(xml): return {}
    txt = open(xml,encoding="utf-8").read()
    return {t:b for t,b in re.findall(r'text="([^"]+)"[^>]*bounds="(\[[0-9,\[\]]+\])"',txt)}

def tap(bs):
    ns=list(map(int,re.findall(r"\d+",bs))); cx=(ns[0]+ns[2])//2; cy=(ns[1]+ns[3])//2
    adb("shell","input","tap",str(cx),str(cy)); return cx,cy

def dump(name):
    p=f"{TMPDIR}/{name}"; adb("shell","uiautomator","dump","/sdcard/ui_tmp.xml"); adb("pull","/sdcard/ui_tmp.xml",p); return p

print("Phase 5 capture")
adb("reverse",f"tcp:{PORT}",f"tcp:{PORT}")
print(f"Recorder -> {OUTPUT}")
rec=subprocess.Popen([sys.executable,"tools/m15c_rtmp_flv_recorder.py",str(PORT),OUTPUT,str(DURATION)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
time.sleep(1.5)
adb("shell","am","force-stop","com.fairylive.app"); time.sleep(0.5)
adb("shell","am","start","-n","com.fairylive.app/.MainActivity"); time.sleep(3.5)
e=ui_bounds(dump("p5_go.xml"))
sk=next((k for k in e if "Start Live" in k),None)
if sk: cx,cy=tap(e[sk]); print(f"Tapped {sk} at {cx},{cy}")
else: adb("shell","input","tap","540","1741"); print("Fallback Start Live tap")
time.sleep(2)
e2=ui_bounds(dump("p5_c.xml"))
ck=next((k for k in e2 if k.strip()=="Start"),None)
if ck: cx,cy=tap(e2[ck]); print(f"Consent at {cx},{cy}"); time.sleep(3)
else: print("No consent dialog")
e3=ui_bounds(dump("p5_l.xml"))
print("LIVE:",any("LIVE" in k or "Stop Broadcast" in k for k in e3))
print(f"Waiting {DURATION}s...")
try: out,_=rec.communicate(timeout=DURATION+10); print(out[-400:])
except subprocess.TimeoutExpired: rec.kill(); print("Recorder timed out")
e4=ui_bounds(dump("p5_s.xml"))
sk2=next((k for k in e4 if "Stop" in k and ("Broadcast" in k or "Live" in k)),None)
if sk2: tap(e4[sk2]); print(f"Stopped via {sk2}")
else: adb("shell","input","tap","540","1430"); print("Fallback stop")
sz=os.path.getsize(OUTPUT) if os.path.exists(OUTPUT) else 0
print(f"FLV: {sz} bytes")
sys.exit(0 if sz>5000 else 1)
