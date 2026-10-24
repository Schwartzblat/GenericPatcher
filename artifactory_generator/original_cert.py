import hashlib
import io
import zipfile

from androguard.core.apk import APK
from loguru import logger

logger.disable('androguard')


def _certificates(apk_bytes):
    apk = APK(apk_bytes, raw=True, skip_analysis=True)
    for certs in (apk.get_certificates_der_v3(), apk.get_certificates_der_v2()):
        if certs:
            return list(certs)
    return [c.dump() for c in apk.get_certificates_v1()]


def original_cert_sha1(path):
    """SHA-1 (uppercase hex) of the single signer of an APK or APK bundle, or None."""
    try:
        with open(path, 'rb') as f:
            data = f.read()
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            names = z.namelist()
            inner = sorted((n for n in names if n.lower().endswith('.apk')), key=lambda n: (n != 'base.apk', n))
            if 'AndroidManifest.xml' in names or not inner:
                apks = iter([data])
            else:
                apks = (z.read(n) for n in inner)
            for apk_bytes in apks:
                certs = _certificates(apk_bytes)
                if certs:
                    distinct = set(certs)
                    if len(distinct) != 1:
                        return None
                    return hashlib.sha1(distinct.pop()).hexdigest().upper()
    except Exception:
        return None
    return None
