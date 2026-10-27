import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import List

from stitch import Stitch

from artifactory_generator.ad_inventory import AdInventoryFinder
from artifactory_generator.iap_inventory import IapInventoryFinder


def get_args_from(argv: List[str]):
    parser = argparse.ArgumentParser(description="Record the desktop ad finder's output for an APK, "
                                                 'to check the on-device port against')
    parser.add_argument('apk', help='APK / XAPK / APKM path')
    parser.add_argument('-o', '--output', dest='output', required=True, help='Output JSON')
    parser.add_argument('-t', '--temp', dest='temp_path', default='./.golden-work',
                        help='Work dir (must not exist; removed afterwards)')
    parser.add_argument('--iap', dest='iap', action='store_true', default=False,
                        help="Also record the IAP finder's IAP_* output")
    return parser.parse_args(argv)


def main(argv: List[str]) -> int:
    args = get_args_from(argv)
    if Path(args.temp_path).exists():
        print(f'Work dir {args.temp_path} already exists; remove it first', file=sys.stderr)
        return 1
    # The finder's defaults: no extra block/allow hosts, harvesting and the caller block on.
    finder_args = argparse.Namespace(temp_path=args.temp_path, block_hosts=[], allow_hosts=[], harvest=True,
                                     caller_block=True)
    artifactory_list = [AdInventoryFinder(finder_args)]
    if args.iap:
        artifactory_list.append(IapInventoryFinder(argparse.Namespace(temp_path=args.temp_path, iap=True)))
    try:
        artifacts = Stitch(apk_path=args.apk, temp_path=args.temp_path, artifactory_list=artifactory_list,
                           external_modules=[], should_sign=False).find_artifacts()
    finally:
        shutil.rmtree(args.temp_path, ignore_errors=True)
    golden = {key: value for key, value in artifacts.items()
              if key.startswith('AD_') or (args.iap and key.startswith('IAP_'))}
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(golden, indent=2, sort_keys=True) + '\n', encoding='utf8')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
