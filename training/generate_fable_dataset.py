#!/usr/bin/env python3
"""Generates Fable Browser v45 structured JSON-action & multi-mode training examples."""
import json
from pathlib import Path

EXAMPLES = [
    {
        "mode": "GAMING_SCREEN_ONLY",
        "owner": False,
        "user": "go to the nerest cow you see on screen",
        "assistant": {
            "reply": "Visually locking onto the nearest cow and steering the on-screen joystick.",
            "action": "gaming_track",
            "target": "cow",
            "textToType": "",
            "pressEnter": False,
            "durationSec": 10,
            "yawPct": 0,
            "pitchPct": 0,
            "holdPrimary": False,
            "jump": False,
            "combat": False,
            "autoExecuteSkill": False,
        },
    },
    {
        "mode": "GAMING_SCREEN_ONLY",
        "owner": False,
        "user": "folow player OraOraRush and stop in 10 seconds",
        "assistant": {
            "reply": "Following player OraOraRush smoothly for 10 seconds using screen-only tracking.",
            "action": "gaming_track",
            "target": "OraOraRush",
            "textToType": "",
            "pressEnter": False,
            "durationSec": 10,
            "yawPct": 0,
            "pitchPct": 0,
            "holdPrimary": False,
            "jump": False,
            "combat": False,
            "autoExecuteSkill": False,
        },
    },
    {
        "mode": "GAMING_SCREEN_ONLY",
        "owner": False,
        "user": "turn cmera right 180",
        "assistant": {
            "reply": "Turning camera 180 degrees to the right via touch drag.",
            "action": "gaming_camera",
            "target": "camera",
            "textToType": "",
            "pressEnter": False,
            "durationSec": 4,
            "yawPct": 180,
            "pitchPct": 0,
            "holdPrimary": False,
            "jump": False,
            "combat": False,
            "autoExecuteSkill": False,
        },
    },
    {
        "mode": "BASIC_BROWSING",
        "owner": False,
        "user": "in search type WebGPU browser benchmarks and press enter",
        "assistant": {
            "reply": "Focusing search input, typing 'WebGPU browser benchmarks', and pressing Enter.",
            "action": "type_text",
            "target": "search",
            "textToType": "WebGPU browser benchmarks",
            "pressEnter": True,
            "durationSec": 5,
            "yawPct": 0,
            "pitchPct": 0,
            "holdPrimary": False,
            "jump": False,
            "combat": False,
            "autoExecuteSkill": False,
        },
    },
    {
        "mode": "GAMING_SCREEN_ONLY",
        "owner": False,
        "user": "find best method on yt to bridge fast in bloxd and do the same",
        "assistant": {
            "reply": "Scanning YouTube tutorials & transcripts for fast bridging, compiling the skill, and replaying on screen.",
            "action": "youtube_method",
            "target": "bridge fast in bloxd",
            "textToType": "",
            "pressEnter": False,
            "durationSec": 12,
            "yawPct": 0,
            "pitchPct": 0,
            "holdPrimary": True,
            "jump": False,
            "combat": False,
            "autoExecuteSkill": True,
        },
    },
    {
        "mode": "BASIC_BROWSING",
        "owner": False,
        "user": "find go to https://example.com and copy the page text and deliver whole page code as in zip file",
        "assistant": {
            "reply": "Opening example.com, copying clean page text, and exporting the full 12-stream page code ZIP.",
            "action": "copy_and_zip",
            "target": "https://example.com",
            "textToType": "",
            "pressEnter": False,
            "durationSec": 10,
            "yawPct": 0,
            "pitchPct": 0,
            "holdPrimary": False,
            "jump": False,
            "combat": False,
            "autoExecuteSkill": False,
        },
    },
]

def main() -> None:
    out_path = Path(__file__).with_name("fable_train.jsonl")
    with out_path.open("w", encoding="utf-8") as f:
        for ex in EXAMPLES:
            sys_msg = (
                f"You are Fable AI Agent v45. Mode: {ex['mode']}. "
                f"OwnerResearchBeta: {ex['owner']}. ReadOnlyNoPageInjection: true. "
                "Respond ONLY with valid Fable action JSON."
            )
            row = {
                "messages": [
                    {"role": "system", "content": sys_msg},
                    {"role": "user", "content": ex["user"]},
                    {"role": "assistant", "content": json.dumps(ex["assistant"], ensure_ascii=False)},
                ]
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(EXAMPLES)} seed rows to {out_path}")

if __name__ == "__main__":
    main()
