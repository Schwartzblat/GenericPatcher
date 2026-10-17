PATCHER_VERSION = "1.0.0"
PACKAGE = "generic"

# Stitch writes this into the target's manifest as the provider's android:name.
# It must match the class in smali_generator/app/src/main/java/com/smali_generator/.
MODULE_PROVIDER = 'com.smali_generator.InitProviderGeneric'

# The paywall module generates a class of this name from PAYWALL_PROVIDER_CLASS,
# and stitch names it in the manifest.
PAYWALL_PROVIDER = 'com.paywall.InitProviderPaywallGeneric'

# Where Stitch writes the finders' results in the patched APK. The module reads
# them back at runtime through Artifacts (assets/patcher/artifactory.json).
ARTIFACTS_ASSET = 'assets/patcher/artifactory.json'
