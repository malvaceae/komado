#!/usr/bin/env python3
"""Connect the local macOS probe and an Android emulator using generated content.

Signaling is copied via adb into the debug app's private storage. This is not
product signaling or a Tailscale/security validation.
"""
import collections
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parent
APP = "me.mallows.komado.probe"
APK = ROOT / "android-webrtc/app/build/outputs/apk/debug/app-debug.apk"
SENDER = ROOT / "macos-webrtc/.build/out/Products/Debug/ExchangeProbe"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != "--screen"):
        raise SystemExit("Usage: python3 experiments/run-exchange-probe.py EMULATOR_SERIAL [--screen]")
    serial = sys.argv[1]
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise SystemExit("Specify a locally connected Android emulator serial.")
    capture_screen = len(sys.argv) == 3
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
            sender_args = [str(SENDER), str(offer), str(answer)]
            if capture_screen:
                sender_args.append("--screen")
            sender = subprocess.Popen(sender_args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            output_lines = collections.deque(maxlen=10000)
            reader = threading.Thread(target=lambda: output_lines.extend(sender.stdout), daemon=True)
            reader.start()
            def sender_output():
                prefixes = ("Offer ready", "ICE state:", "Screen capture dimensions:", "Screen capture frames:",
                            "Capture timestamp", "Last capture timestamp", "Outbound video statistics:",
                            "Data channel acknowledgement", "Exchange probe failed:")
                relevant = [line for line in output_lines if line.startswith(prefixes)]
                return "".join(relevant[-30:])
            deadline = time.monotonic() + 30
            while not offer.is_file():
                if sender.poll() is not None:
                    raise RuntimeError(f"macOS sender stopped: {sender_output()}")
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
            sender_exit_time = None
            while time.monotonic() < deadline:
                if sender.poll() is not None and sender_exit_time is None:
                    sender_exit_time = time.monotonic()
                if sender_exit_time is not None and time.monotonic() - sender_exit_time > 2:
                    raise RuntimeError(f"macOS sender ended before Android received video and data: {sender_output()}")
                result = subprocess.run([*adb, "exec-out", "run-as", APP, "cat", "files/result.json"], capture_output=True)
                if result.returncode == 0 and result.stdout.startswith(b"{"):
                    report = json.loads(result.stdout)
                    if not report.get("success"):
                        sender_output = sender_output() if sender.poll() is not None else "sender still running"
                        raise RuntimeError(f"Android receiver: {report}; macOS sender: {sender_output}")
                    break
                if sender.poll() is not None and sender.returncode != 0:
                    raise RuntimeError(f"macOS sender stopped: {sender_output()}")
                time.sleep(0.2)
            else:
                raise TimeoutError("Android did not receive both video and data")
            sender.wait(timeout=10)
            reader.join(timeout=2)
            output = sender_output()
            if sender.returncode:
                raise RuntimeError(f"macOS sender failed: {output}")
            print(output.strip())
            print(f"Android received {'screen' if capture_screen else 'generated'} frames: {report['frames']}; codec: {report['codec']}; data message received and acknowledged")
    finally:
        if sender and sender.poll() is None:
            sender.terminate()
            sender.wait(timeout=5)
        subprocess.run([*adb, "shell", "am", "force-stop", APP], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([*adb, "shell", "run-as", APP, "rm", "-f", "files/offer.json", "files/offer.json.tmp", "files/answer.json", "files/answer.json.tmp", "files/result.json", "files/result.json.tmp"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
