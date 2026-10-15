"""Which Google Play billing clients this APK carries, and what its own AIDL
proxy says the transaction numbering is.

The transaction codes are a contract with the separately shipped Play Store, so
they are stable -- but they are read out of each APK anyway and checked against
what FakePlayBilling implements, so a Play Billing revision that renumbered them
would be reported at patch time instead of silently misrouting every purchase.

Two proxy shapes are parsed:

  obfuscated   R8 funnels every call through a helper, helper(code, parcel),
               with the code as a literal just before the call.
  classic      aidl's own output calls IBinder.transact directly, with the code
               in a static TRANSACTION_<name> field.
"""
import re
from pathlib import Path

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder
from stitch.common import EXTRACTED_PATH

from artifactory_generator.iap_catalog import (BILLING_DESCRIPTOR, EXPECTED_TX, IAP_CLIENTS,
                                               TX_ARG_SHAPES, VERIFIER_ANCHORS)

METHOD_RE = re.compile(
    r'^\.method[^\n]*?\s(?P<name>[\w$<>]+)\((?P<params>[^)]*)\)(?P<ret>\S+)\n(?P<body>.*?)^\.end method',
    re.S | re.M)
# An obfuscated proxy funnels through a helper: helper(code, parcel) -> Parcel.
HELPER_CALL_RE = re.compile(
    r'invoke-virtual \{p0, (?P<reg>[vp]\d+), [vp]\d+\}, \S+->\S+\(ILandroid/os/Parcel;\)')
# aidl's own output calls IBinder.transact directly.
TRANSACT_CALL_RE = re.compile(
    r'invoke-interface \{[vp]\d+, (?P<reg>[vp]\d+), [vp]\d+, [vp]\d+, [vp]\d+\}, '
    r'Landroid/os/IBinder;->transact\(')
FIELD_RE = re.compile(r'^\.field[^\n]*?\s(?P<name>\w+):I = (?P<value>0x[0-9a-f]+|\d+)', re.M)

CLASS_NAME_RE = re.compile(r'^\.class[^\n]* L(?P<name>\S+);', re.M)
# Google's sample verifier. The canonical shape is
# verifyPurchase(base64PublicKey, signedData, signature); some copies drop the
# key argument, so both are accepted and the signature is emitted as found.
VERIFIER_METHOD_RE = re.compile(
    r'^\.method [\w ]*static (?P<name>\w+)\((?P<args>(?:Ljava/lang/String;){2,3})\)Z$', re.M)


def _const_for(body: str, reg: str, upto: int):
    """The last literal assigned to reg before offset upto."""
    pattern = re.compile(r'const(?:/4|/16|/high16)? ' + re.escape(reg) + r', (0x[0-9a-f]+|-?\d+)')
    values = pattern.findall(body[:upto])
    return int(values[-1], 0) if values else None


def _sget_for(body: str, reg: str, upto: int, fields: dict):
    """The static int field last read into reg before offset upto."""
    pattern = re.compile(r'sget ' + re.escape(reg) + r', \S+->(\w+):I')
    names = pattern.findall(body[:upto])
    return fields.get(names[-1]) if names else None


def parse_tx_map(smali: str) -> dict:
    """Map each transaction code this proxy sends to the sender's param string."""
    fields = {m.group('name'): int(m.group('value'), 0) for m in FIELD_RE.finditer(smali)}
    tx_map = {}
    for method in METHOD_RE.finditer(smali):
        body = method.group('body')
        call = HELPER_CALL_RE.search(body) or TRANSACT_CALL_RE.search(body)
        if call is None:
            continue
        reg = call.group('reg')
        code = _const_for(body, reg, call.start())
        if code is None:
            code = _sget_for(body, reg, call.start(), fields)
        if code is not None:
            tx_map[code] = method.group('params')
    return tx_map


def tx_mismatches(tx_map: dict) -> list:
    """Codes whose sender does not have the shape we implement for them."""
    problems = []
    for code, shape in TX_ARG_SHAPES.items():
        found = tx_map.get(code)
        if found is not None and found != shape:
            problems.append(f'tx {code} ({EXPECTED_TX[code]}): expected ({shape}), proxy sends ({found})')
    return problems


class IapInventoryFinder(SimpleArtifactoryFinder):
    """Emits IAP_ENABLED, IAP_CLIENTS_PRESENT and IAP_VERIFIER_*.

    No product-id list is emitted: the ids live in the game's own code, not in
    the billing library's, and the runtime learns each one exactly from the
    getSkuDetails request that asks about it.

    Always fires, with empty values for an app that has no billing code, so the
    gate and the patch behave the same for every APK.
    """

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False
        self.enabled = bool(getattr(args, 'iap', False))

    def class_filter(self, class_data: str) -> bool:
        return not self.is_found

    def _smali_roots(self):
        extracted = Path(self.args.temp_path) / EXTRACTED_PATH
        return sorted(p for p in extracted.iterdir() if p.is_dir() and p.name.startswith('smali'))

    def _client_classes(self, roots, client):
        for package in client.packages:
            rel = package.rstrip('.').replace('.', '/')
            for root in roots:
                directory = root / rel
                if directory.is_dir():
                    yield from directory.rglob('*.smali')

    def _scan_for_targets(self, roots):
        """One pass over every class: the AIDL sender, and the sample verifier.

        Both anchors are looked for together because each needs the whole tree
        and a typical game is 40k classes -- two passes would double the cost of
        a patch for nothing.
        """
        proxy = None
        verifier = None
        for root in roots:
            for path in root.rglob('*.smali'):
                data = path.read_text(encoding='utf8', errors='replace')
                if verifier is None and any(anchor in data for anchor in VERIFIER_ANCHORS):
                    method = VERIFIER_METHOD_RE.search(data)
                    name = CLASS_NAME_RE.search(data)
                    if method and name:
                        verifier = (name.group('name').replace('/', '.'), method.group('name'),
                                    f"({method.group('args')})Z")
                    elif name:
                        print('[!] IapInventory: ' + name.group('name').replace('/', '.')
                              + ' looks like the sample verifier but has no static'
                              + ' (String, String[, String]) -> boolean method; not hooked')
                if BILLING_DESCRIPTOR in data:
                    tx_map = parse_tx_map(data)
                    if proxy is None or len(tx_map) > len(proxy[1]):
                        proxy = (path, tx_map)
        return proxy, verifier

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        roots = self._smali_roots()

        present = []
        for client in IAP_CLIENTS:
            classes = sum(1 for _ in self._client_classes(roots, client))
            if classes:
                present.append((client.name, classes))

        # Scan whenever the layer is on, even if no catalogued client matched:
        # an app can embed the AIDL under a package this catalog does not name.
        scan = bool(present) or self.enabled
        proxy, verifier = self._scan_for_targets(roots) if scan else (None, None)

        artifacts['IAP_ENABLED'] = 'true' if self.enabled else 'false'
        artifacts['IAP_CLIENTS_PRESENT'] = ', '.join(name for name, _ in present)
        artifacts['IAP_VERIFIER_CLASS_NAME'] = verifier[0] if verifier else ''
        artifacts['IAP_VERIFIER_METHOD_NAME'] = verifier[1] if verifier else ''
        artifacts['IAP_VERIFIER_METHOD_SIG'] = verifier[2] if verifier else ''
        self.is_found = True

        print(f'[+] IapInventory: {len(present)} billing client(s) in the APK'
              + ('' if self.enabled else ', layer disabled (pass --iap to enable)'))
        for name, classes in present:
            print(f'      {name:<28} {classes:>6} classes')
        if present:
            if proxy is None:
                print('[!] IapInventory: no class sends the billing AIDL; the fake service may never be asked')
            else:
                path, tx_map = proxy
                print(f'[+] IapInventory: {path.name} sends {len(tx_map)} transaction(s)')
                for problem in tx_mismatches(tx_map):
                    print(f'[!] IapInventory: {problem}')
        if verifier:
            print(f'[+] IapInventory: receipt verifier {verifier[0]}.{verifier[1]}')
