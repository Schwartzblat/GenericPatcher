"""Which ad SDKs this APK carries, and which hosts their own code talks to.

A generic patcher has no single target class to find. What does vary from app
to app, and from one SDK release to the next, is the set of SDKs bundled and
the endpoints they use. So this finder:

  1. checks every SDK in ad_catalog.SDKS for its package in the extracted smali;
  2. harvests the hosts of every URL literal (and bare hostname literal) in those
     SDKs' classes, so an endpoint added in a new SDK release is blocked without
     a catalog update;
  3. hands the result, with the catalog's curated lists, to AdRules.java.

It always fires, with empty values when an app has no ads at all, so the gate
and the patch behave the same for every APK.

It runs as a one-shot (class_filter is True on the first class only) and walks
just the SDK package directories itself, rather than being offered all 40k
classes of a typical game one at a time.
"""
import re
from collections import defaultdict
from pathlib import Path

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder
from stitch.common import EXTRACTED_PATH

from artifactory_generator.ad_catalog import (APP_FRAME_PACKAGES, HARVEST_BARE_TLDS, PROTECTED_HOSTS, SDKS,
                                              URL_RULES)

CONST_STRING_RE = re.compile(r'^\s*const-string(?:/jumbo)? [vp]\d+, "(.*)"$', re.M)
URL_HOST_RE = re.compile(r'\b(?:https?|wss?)://([A-Za-z0-9.-]+)(?=[:/?#\\]|$)')
HOSTNAME_RE = re.compile(r'^(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}$')
IPV4_RE = re.compile(r'^\d+(?:\.\d+){3}$')
# A bare literal starting like a Java package is a class or package name, not a host.
PACKAGE_ROOTS = frozenset(('com', 'org', 'net', 'io', 'android', 'androidx', 'java', 'javax', 'kotlin', 'dalvik'))

# One Java string literal holds at most 65535 bytes. Stay well under it.
MAX_HARVEST_CHARS = 48000


def _matches_suffix(host: str, suffixes) -> bool:
    return any(host == s or host.endswith('.' + s) for s in suffixes)


def _hosts_in_literal(literal: str):
    for match in URL_HOST_RE.finditer(literal):
        yield match.group(1)
    if '/' not in literal and ' ' not in literal and literal.count('.') >= 1:
        bare = literal.lower()
        if (HOSTNAME_RE.match(bare) and bare.rsplit('.', 1)[1] in HARVEST_BARE_TLDS
                and bare.split('.', 1)[0] not in PACKAGE_ROOTS):
            yield bare


def _clean_host(raw: str):
    host = raw.lower().strip('.')
    if not HOSTNAME_RE.match(host) or IPV4_RE.match(host):
        return None
    return host


class AdInventoryFinder(SimpleArtifactoryFinder):
    """Emits AD_SDKS_PRESENT, AD_SDK_PACKAGES, AD_APP_FRAME_PACKAGES, AD_HOST_SUFFIXES,
    AD_HOSTS_HARVESTED, AD_PROTECTED_SUFFIXES, AD_URL_RULES and AD_CALLER_BLOCK."""

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self.extra_block = [h.lower() for h in getattr(args, 'block_hosts', None) or []]
        self.extra_allow = [h.lower() for h in getattr(args, 'allow_hosts', None) or []]
        self.harvest = getattr(args, 'harvest', True)
        self.caller_block = getattr(args, 'caller_block', True)

    def class_filter(self, class_data: str) -> bool:
        return not self.is_found

    def _smali_roots(self):
        extracted = Path(self.args.temp_path) / EXTRACTED_PATH
        return sorted(p for p in extracted.iterdir() if p.is_dir() and p.name.startswith('smali'))

    def _sdk_classes(self, roots, sdk):
        for package in sdk.packages:
            rel = package.rstrip('.').replace('.', '/')
            for root in roots:
                directory = root / rel
                if directory.is_dir():
                    yield from directory.rglob('*.smali')

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        roots = self._smali_roots()
        block_suffixes = [h for sdk in SDKS for h in sdk.hosts] + self.extra_block
        protected = [h for h in PROTECTED_HOSTS if h not in self.extra_block] + self.extra_allow

        present = []
        harvested = defaultdict(set)
        for sdk in SDKS:
            if not sdk.packages:
                continue
            classes = 0
            for path in self._sdk_classes(roots, sdk):
                classes += 1
                if not self.harvest:
                    continue
                data = path.read_text(encoding='utf8', errors='replace')
                for literal in CONST_STRING_RE.findall(data):
                    for raw in _hosts_in_literal(literal):
                        host = _clean_host(raw)
                        if host is None or _matches_suffix(host, block_suffixes) or _matches_suffix(host, protected):
                            continue
                        harvested[host].add(sdk.name)
            if classes:
                present.append((sdk.name, classes))

        hosts = sorted(harvested)
        joined = ','.join(hosts)
        if len(joined) > MAX_HARVEST_CHARS:
            print(f'[!] AdInventory: {len(hosts)} harvested hosts exceed {MAX_HARVEST_CHARS} chars, truncating')
            joined = joined[:MAX_HARVEST_CHARS].rsplit(',', 1)[0]

        artifacts['AD_SDKS_PRESENT'] = ', '.join(name for name, _ in present)
        artifacts['AD_SDK_PACKAGES'] = ','.join(p for sdk in SDKS for p in sdk.packages)
        artifacts['AD_APP_FRAME_PACKAGES'] = ','.join(APP_FRAME_PACKAGES)
        artifacts['AD_HOST_SUFFIXES'] = ','.join(block_suffixes)
        artifacts['AD_HOSTS_HARVESTED'] = joined
        artifacts['AD_PROTECTED_SUFFIXES'] = ','.join(protected)
        artifacts['AD_URL_RULES'] = ','.join(host + path for host, path in URL_RULES)
        artifacts['AD_CALLER_BLOCK'] = 'true' if self.caller_block else 'false'
        self.is_found = True

        print(f'[+] AdInventory: {len(present)} ad SDK(s) in the APK')
        for name, classes in present:
            from_sdk = sorted(h for h, owners in harvested.items() if name in owners)
            print(f'      {name:<28} {classes:>6} classes, {len(from_sdk):>3} harvested host(s)')
        print(f'[+] AdInventory: {len(hosts)} harvested host(s) blocked on top of {len(block_suffixes)} curated suffixes')
