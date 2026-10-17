import argparse
from pathlib import Path

from stitch import Stitch
from stitch.common import ExternalModule

# Signature finders live in ./artifactory_generator/ and are registered in
# artifactory_list below. What they block is configured in ad_catalog.py.
from artifactory_generator.ad_inventory import AdInventoryFinder
from constants import ARTIFACTS_ASSET, MODULE_PROVIDER, PAYWALL_PROVIDER


def get_args():
    parser = argparse.ArgumentParser(description='Remove ads from any Android app.')
    parser.add_argument('-p', '--apk-path', dest='apk_path', help='APK / XAPK / APKM path', required=True)
    parser.add_argument('-o', '--output', dest='output', help='Output APK path', required=False,
                        default='output.apk')
    parser.add_argument('-t', '--temp', dest='temp_path', help='Temp path for extracted content', required=False,
                        default='./temp')
    parser.add_argument('--arch', dest='arch', help='ABI whose libarthooks.so gets injected', required=False,
                        default='arm64-v8a',
                        choices=['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'])
    parser.add_argument('--no-sign', dest='should_sign', help='Whether to sign the output APK',
                        action='store_false', required=False, default=True)
    parser.add_argument('--block-host', dest='block_hosts', metavar='HOST', nargs='+', default=[],
                        help='Also block these hosts and their subdomains')
    parser.add_argument('--allow-host', dest='allow_hosts', metavar='HOST', nargs='+', default=[],
                        help='Never block these hosts or their subdomains by host rule')
    parser.add_argument('--no-harvest', dest='harvest', action='store_false', default=True,
                        help="Block only the curated hosts, not the ones found in the APK's ad SDK code")
    parser.add_argument('--no-caller-block', dest='caller_block', action='store_false', default=True,
                        help='Block by host only, not every request that ad SDK code makes')
    parser.add_argument('--extra-artifacts', dest='extra_artifacts',
                        help='Extra artifacts for the artifactory, in the format "key:value"',
                        required=False, default=[], nargs='+')
    parser.add_argument('--paywall', dest='paywall', help='Path of the PatchPaywall module to add', required=False,
                        default=None)
    args, _ = parser.parse_known_args()
    return args


def main():
    args = get_args()
    extra_artifacts = {artifact.split(':', 1)[0]: artifact.split(':', 1)[1] for artifact in args.extra_artifacts}
    external_modules = [
        ExternalModule(Path(__file__).parent / './smali_generator', MODULE_PROVIDER)
    ]
    if args.paywall is not None:
        extra_artifacts.setdefault('PAYWALL_PROVIDER_CLASS', PAYWALL_PROVIDER.rsplit('.', 1)[1])
        external_modules.append(ExternalModule(Path(args.paywall), PAYWALL_PROVIDER))
    artifactory_list = [
        AdInventoryFinder(args),
    ]
    with Stitch(
            apk_path=args.apk_path,
            output_apk=args.output,
            temp_path=args.temp_path,
            artifactory_list=artifactory_list,
            external_modules=external_modules,
            arch=args.arch,
            should_sign=args.should_sign,
            extra_artifacts=extra_artifacts,
            artifacts_asset=ARTIFACTS_ASSET,
    ) as stitch:
        stitch.patch()


if __name__ == '__main__':
    main()
