#!/usr/bin/env python3
"""Connect the local macOS probe and an Android emulator using generated content.

Signaling is copied via adb into the debug app's private storage. This is not
product signaling or a Tailscale/security validation.
"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent
APP = "me.mallows.komado.probe"
APK = ROOT / "android-webrtc/app/build/outputs/apk/debug/app-debug.apk"
SENDER = ROOT / "macos-webrtc/.build/out/Products/Debug/ExchangeProbe"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 experiments/run-exchange-probe.py EMULATOR_SERIAL")
    serial = sys.argv[1]
    adb = ["adb", "-s", serial]
    if run(*adb, "shell", "getprop", "ro.kernel.qemu", capture_output=True, text=True).stdout.strip() != "1":
        raise SystemExit("This probe only runs against an Android emulator.")
    if not APK.is_file() or not SENDER.is_file():
        raise SystemExit("Build the Android debug APK and macOS ExchangeProbe first.")

    run(*adb, "install", "-r", str(APK), stdout=subprocess.DEVNULL)
    run(*adb, "shell", "am", "force-stop", APP)
    run(*adb, "shell", "am", "start", "-W", "-n", f"{APP}/.MainActivity", "--ez", "exchangeProbe", "true", stdout=subprocess.DEVNULL)
    sender = None
    try:
        with tempfile.TemporaryDirectory(prefix="komado-exchange-") as directory:
            offer = Path(directory) / "offer.json"
            answer = Path(directory) / "answer.json"
            sender = subprocess.Popen([str(SENDER), str(offer), str(answer)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            deadline = time.monotonic() + 30
            while not offer.is_file():
                if sender.poll() is not None:
                    raise RuntimeError(f"macOS sender stopped: {sender.stdout.read()}")
                if time.monotonic() > deadline:
                    raise TimeoutError("macOS offer was not produced")
                time.sleep(0.1)
            run(*adb, "shell", f"run-as {APP} sh -c 'cat > files/offer.json.tmp && mv files/offer.json.tmp files/offer.json'", input=offer.read_bytes())
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                result = subprocess.run([*adb, "exec-out", "run-as", APP, "cat", "files/answer.json"], capture_output=True)
                if result.returncode == 0 and result.stdout.startswith(b"{"):
                    answer.write_bytes(result.stdout)
                    break
                failure = subprocess.run([*adb, "exec-out", "run-as", APP, "cat", "files/result.json"], capture_output=True)
                if failure.returncode == 0 and failure.stdout.startswith(b"{"):
                    raise RuntimeError(f"Android receiver: {json.loads(failure.stdout)}")
                time.sleep(0.2)
            else:
                raise TimeoutError("Android answer was not produced")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = subprocess.run([*adb, "exec-out", "run-as", APP, "cat", "files/result.json"], capture_output=True)
                if result.returncode == 0 and result.stdout.startswith(b"{"):
                    report = json.loads(result.stdout)
                    if not report.get("success"):
                        raise RuntimeError(f"Android receiver: {report}")
                    break
                if sender.poll() is not None and sender.returncode != 0:
                    raise RuntimeError(f"macOS sender stopped: {sender.stdout.read()}")
                time.sleep(0.2)
            else:
                raise TimeoutError("Android did not receive both video and data")
            output, _ = sender.communicate(timeout=10)
            if sender.returncode:
                raise RuntimeError(f"macOS sender failed: {output}")
            print(output.strip())
            print(f"Android received generated frames: {report['frames']}; codec: {report['codec']}; data message received and acknowledged")
    finally:
        if sender and sender.poll() is None:
            sender.terminate()
            sender.wait(timeout=5)
        subprocess.run([*adb, "shell", "am", "force-stop", APP], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([*adb, "shell", "run-as", APP, "rm", "-f", "files/offer.json", "files/offer.json.tmp", "files/answer.json", "files/answer.json.tmp", "files/result.json", "files/result.json.tmp"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
