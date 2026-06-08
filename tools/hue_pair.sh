#!/usr/bin/env bash
# Pair with the Philips Hue bridge to obtain an application key.
# Run this script on the same network as the bridge.
#
# Usage:
#   1. Find your bridge IP: https://discovery.meethue.com/  (or Hue app settings)
#   2. PRESS the round link button on top of the bridge.
#   3. Within 30 seconds run:  ./hue_pair.sh <bridge-ip>
#
# The "username" field in the response is your Hue application key.
set -euo pipefail

IP="${1:-}"
if [[ -z "$IP" ]]; then
  echo "usage: $0 <bridge-ip>" >&2
  exit 1
fi

echo "Requesting application key from https://$IP/api ..."
curl -sk -X POST "https://$IP/api" \
  -H 'Content-Type: application/json' \
  -d '{"devicetype":"portalhome#portal","generateclientkey":true}'
echo
echo "If you see 'link button not pressed', press the button and re-run within 30s."
