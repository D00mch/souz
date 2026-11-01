from __future__ import annotations

import sys

# Must be set before importing local modules below — the skill registry rejects any bundle
# containing a .pyc (FileSystemSkillRegistryRepository disallows the extension), and a
# __pycache__/ directory is exactly what a plain `import` would otherwise leave behind on every
# run, breaking the *next* invocation's bundle load.
sys.dont_write_bytecode = True

import json

# No network calls and no token live here. Every API call to api.iot.yandex.net is made by the
# model via SafeApiCall (the backend injects the OAuth token; this script never sees it). This
# script only does local JSON processing for the get_state/control_device composite commands
# declared in SKILL.md: parsing rooms/devices lists, resolving a device by name, and building/
# checking a control action. Called with the relevant JSON blob (a prior step's SafeApiCall
# 'body') as a positional argument, not via stdin — composite steps pass values through `args`.

ON_OFF = {
    "on": True, "1": True, "true": True, "вкл": True, "включи": True, "включить": True,
    "off": False, "0": False, "false": False, "выкл": False, "выключи": False, "выключить": False,
}


def die(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def usage() -> None:
    die(
        "Usage: ysh_api.py {rooms <user_info_json>|devices <user_info_json>|"
        "devices-compact <user_info_json>|resolve-device-id <user_info_json> <name>|"
        "build-action-body <device_id> <on|off>|check-action-result <actions_json> <device_id>}"
    )


def load_json_or_die(raw: str, step: str) -> dict:
    if not raw:
        die(
            f"ERROR: '{step}' needs a JSON blob argument, but it was empty. Pass a SafeApiCall "
            f"response's 'body' field for this argument."
        )
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        die(f"ERROR: '{step}' needs a JSON blob argument, but it wasn't valid JSON.")


def passthrough_if_reconnect(obj: dict) -> None:
    """SafeApiCall returns a normal (non-error) response with reconnectRequired=true when the
    OAuth connection is missing or needs refreshing, instead of raising an error. Pass that
    straight through rather than trying to interpret it as rooms/devices/action data, so the
    model still sees authorizationUrl and can follow SKILL.md's "Если аккаунт ещё не подключён"
    instructions — this is what the composite command as a whole returns to the model in that
    case."""
    if obj.get("reconnectRequired"):
        print(json.dumps(obj, ensure_ascii=False))
        raise SystemExit(0)


def print_rooms(obj: dict) -> None:
    for room in obj.get("rooms") or []:
        print(f"{room.get('id', '')}\t{room.get('name', '')}")


def print_devices(obj: dict) -> None:
    for device in obj.get("devices") or []:
        room = device.get("room") or ""
        print(f"{device.get('id', '')}\t{device.get('name', '')}\t{device.get('type', '')}\troom={room}")


def print_devices_compact(obj: dict) -> None:
    room_names = {room.get("id"): room.get("name", "") for room in (obj.get("rooms") or [])}
    for device in obj.get("devices") or []:
        room_name = room_names.get(device.get("room"), "")
        device_id = device.get("id", "")
        name = device.get("name", "")
        status = "unknown"
        for capability in device.get("capabilities") or []:
            if capability.get("type") == "devices.capabilities.on_off":
                value = (capability.get("state") or {}).get("value")
                status = "on" if value is True else "off" if value is False else "unknown"
                break
        if status != "unknown":
            print(f"- {room_name} | {name} | {device_id} | {status}")


def resolve_device_id(obj: dict, name: str) -> None:
    needle = name.strip().lower()
    matches = [
        device.get("id")
        for device in (obj.get("devices") or [])
        if (device.get("name") or "").strip().lower() == needle
    ]
    if not matches:
        die(f"ERROR: device not found by name: {name}")
    if len(matches) > 1:
        print(f"WARN: multiple devices named {name!r}, using first id {matches[0]}", file=sys.stderr)
    print(matches[0])


def build_action_body(device_id: str, onoff: str) -> None:
    value = ON_OFF.get(onoff.strip().lower())
    if value is None:
        die("ERROR: second arg must be on|off")
    print(json.dumps({
        "devices": [{
            "id": device_id,
            "actions": [{"type": "devices.capabilities.on_off", "state": {"instance": "on", "value": value}}],
        }],
    }))


def check_action_result(obj: dict, expected_id: str) -> None:
    devices = obj.get("devices") or []
    if not devices:
        die(f"ERROR: no device action result for {expected_id}")
    for device in devices:
        if device.get("id") != expected_id:
            continue
        for capability in device.get("capabilities") or []:
            result = (capability.get("state") or {}).get("action_result") or {}
            status = result.get("status")
            if status == "DONE":
                print(json.dumps(obj, ensure_ascii=False))
                return
            if status:
                die("ERROR: " + json.dumps(result, ensure_ascii=False))
    die(f"ERROR: no DONE result for {expected_id}")


def main() -> None:
    args = sys.argv[1:]
    if not args:
        usage()
        return
    cmd, rest = args[0], args[1:]

    if cmd in ("rooms", "devices", "devices-compact"):
        obj = load_json_or_die(rest[0] if rest else "", cmd)
        passthrough_if_reconnect(obj)
        {"rooms": print_rooms, "devices": print_devices, "devices-compact": print_devices_compact}[cmd](obj)
    elif cmd == "resolve-device-id":
        if len(rest) < 2:
            die("ERROR: resolve-device-id requires <user_info_json> <name>")
        obj = load_json_or_die(rest[0], cmd)
        passthrough_if_reconnect(obj)
        resolve_device_id(obj, rest[1])
    elif cmd == "build-action-body":
        if len(rest) < 2:
            die("ERROR: build-action-body requires <device_id> <on|off>")
        build_action_body(rest[0], rest[1])
    elif cmd == "check-action-result":
        if len(rest) < 2:
            die("ERROR: check-action-result requires <actions_json> <device_id>")
        obj = load_json_or_die(rest[0], cmd)
        passthrough_if_reconnect(obj)
        check_action_result(obj, rest[1])
    else:
        die(f"Unknown command: {cmd}")


if __name__ == "__main__":
    main()
