"""Build KinoWatch episode/resume intents without opening a device or reading cookies."""
import argparse
import json
import re


def positive_android_int(value):
    if not re.fullmatch(r"[1-9][0-9]{0,9}", value) or int(value) > 2_147_483_647:
        raise argparse.ArgumentTypeError("Требуется положительное целое число до 2147483647.")
    return int(value)


def main():
    parser = argparse.ArgumentParser(description="Prepare an episode or resume link for KinoWatch")
    commands = parser.add_subparsers(dest="mode", required=True)
    episode = commands.add_parser("episode", help="Select a specific season and episode")
    episode.add_argument("item_id", type=positive_android_int)
    episode.add_argument("season", type=positive_android_int)
    episode.add_argument("episode", type=positive_android_int)
    resume = commands.add_parser("resume", help="Let KinoWatch select the first unfinished episode")
    resume.add_argument("item_id", type=positive_android_int)
    args = parser.parse_args()
    if args.mode == "episode":
        uri = f"https://kino.watch/item/view/{args.item_id}/s{args.season}e{args.episode}"
    else:
        uri = f"tvrecommendation://app/playback/{args.item_id}"
    print(json.dumps({"uri": uri, "packageName": "watch.kino"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
