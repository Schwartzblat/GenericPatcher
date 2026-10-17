from artifactory_generator import ad_inventory
from artifactory_generator.ad_catalog import APP_FRAME_PACKAGES, PROTECTED_HOSTS, SDKS, URL_RULES, HARVEST_BARE_TLDS

CATALOG_FORMAT = 1


def catalog_json() -> dict:
    """The ad catalog as plain JSON, for the on-device patcher's Kotlin port of the finder.

    The lists that are sets in Python are sorted so the bundle bytes do not depend on hash order."""
    return {
        'format': CATALOG_FORMAT,
        'sdks': [{'name': sdk.name, 'packages': list(sdk.packages), 'hosts': list(sdk.hosts)} for sdk in SDKS],
        'protected_hosts': list(PROTECTED_HOSTS),
        'url_rules': [[host, path] for host, path in URL_RULES],
        'app_frame_packages': list(APP_FRAME_PACKAGES),
        'harvest_bare_tlds': sorted(HARVEST_BARE_TLDS),
        'package_roots': sorted(ad_inventory.PACKAGE_ROOTS),
    }
