#!/usr/bin/env python3
"""Compare gathered ICE candidate interfaces without printing addresses or SDP."""
import collections
import ipaddress
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time

SENDER = Path(__file__).resolve().parent / "macos-webrtc/.build/out/Products/Debug/ExchangeProbe"


def normalized(value):
    try:
        return str(ipaddress.ip_address(value.split("%")[0]))
    except ValueError:
        return value


def main():
    if not SENDER.is_file():
        raise SystemExit("Build ExchangeProbe first.")
    tailscale_addresses = set()
    for flag in ("-4", "-6"):
        result = subprocess.run(["tailscale", "ip", flag], capture_output=True, text=True)
        if result.returncode == 0:
            tailscale_addresses.update(normalized(value) for value in result.stdout.split())
    if not tailscale_addresses:
        raise SystemExit("Tailscale addresses could not be obtained.")

    interfaces = {}
    interface = None
    for line in subprocess.check_output(["ifconfig"], text=True).splitlines():
        match = re.match(r"^([^\s:]+):", line)
        if match:
            interface = match.group(1)
        match = re.match(r"\s+inet6?\s+(\S+)", line)
        if match and interface:
            interfaces[normalized(match.group(1))] = interface

    for filter_option in (None, "--filter-known-non-vpn", "--ignore-vpn"):
        with tempfile.TemporaryDirectory(prefix="komado-ice-") as directory:
            offer = Path(directory) / "offer.json"
            answer = Path(directory) / "answer.json"
            args = [str(SENDER), str(offer), str(answer)]
            if filter_option:
                args.append(filter_option)
            process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 30
                while not offer.is_file() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.1)
                if not offer.is_file():
                    raise RuntimeError("ICE gathering did not produce an offer")
                data = json.loads(offer.read_text())
                sdp = data["sdp"]
                network_types = {normalized(address): kind for address, kind in data.get("networkTypes", {}).items()}
                addresses = {
                    normalized(line.split()[4])
                    for line in sdp.splitlines()
                    if line.startswith("a=candidate:") and len(line.split()) > 4
                }
                counts = collections.Counter(
                    "Tailscale" if address in tailscale_addresses else interfaces.get(address, "unknown")
                    for address in addresses
                )
                type_counts = collections.Counter(
                    ("Tailscale" if address in tailscale_addresses else interfaces.get(address, "unknown"),
                     network_types.get(address, "not reported"))
                    for address in addresses
                )
                print(f"Filter: {filter_option or 'none'}; candidate interfaces: {dict(counts)}")
                print(f"WebRTC network types by interface: {dict(type_counts)}")
            finally:
                if process.poll() is None:
                    process.terminate()
                process.wait(timeout=5)


if __name__ == "__main__":
    main()
