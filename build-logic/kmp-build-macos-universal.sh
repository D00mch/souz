#!/bin/bash
#
# Build script for creating universal macOS App Store package
# This script builds both x86_64 and arm64 versions and combines them using lipo
#
# Usage: ./ci/build-macos-universal.sh [build_number]
# Example: ./ci/build-macos-universal.sh 23
#
# Configuration is read from:
#   - local.properties (signing identities)
#   - build.gradle.kts (version, app name)
#   - Environment variables (can override local.properties)
#

set -e  # Exit on error

# =============================================================================
# Paths
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
RESOURCES_DIR="$PROJECT_DIR/composeApp/src/jvmMain/resources"
GENERATED_NATIVE_RESOURCES_DIR="$PROJECT_DIR/composeApp/build/generated/native-resources"
BUILD_DIR="$PROJECT_DIR/composeApp/build/compose/binaries"
COMPOSE_RUNTIME_CACHE_DIR="$PROJECT_DIR/composeApp/build/compose/tmp/main/runtime"
COMPOSE_CHECK_RUNTIME_DIR="$PROJECT_DIR/composeApp/build/compose/tmp/checkRuntime"
UNIVERSAL_BUILD_DIR="$PROJECT_DIR/composeApp/build/universal-build"
LOCAL_PROPERTIES="$PROJECT_DIR/local.properties"
BUILD_GRADLE="$PROJECT_DIR/composeApp/build.gradle.kts"

# =============================================================================
# Helper functions
# =============================================================================

log_info() {
    echo -e "\033[1;34m[INFO]\033[0m $1"
}

log_success() {
    echo -e "\033[1;32m[SUCCESS]\033[0m $1"
}

log_error() {
    echo -e "\033[1;31m[ERROR]\033[0m $1"
}

log_step() {
    echo ""
    echo -e "\033[1;36m========================================\033[0m"
    echo -e "\033[1;36m$1\033[0m"
    echo -e "\033[1;36m========================================\033[0m"
}

# Read property from local.properties
read_property() {
    local key="$1"
    local file="$2"
    if [ -f "$file" ]; then
        grep "^${key}=" "$file" 2>/dev/null | cut -d'=' -f2- | tr -d '\r'
    fi
}

# Extract value from build.gradle.kts
extract_gradle_value() {
    local pattern="$1"
    grep "$pattern" "$BUILD_GRADLE" | head -1 | sed 's/.*"\([^"]*\)".*/\1/'
}

resolve_jdk_home() {
    local arch="$1"
    local env_hint="$2"
    local jdk_home

    if [ ! -x "/usr/libexec/java_home" ]; then
        log_error "/usr/libexec/java_home is not available."
        log_info "This script must run on macOS with a valid JDK installation."
        exit 1
    fi

    if ! jdk_home="$(/usr/libexec/java_home -v 21 -a "$arch" 2>/dev/null)"; then
        log_error "JDK 21 for architecture '$arch' was not found."
        log_info "Install JDK 21 for '$arch' or export $env_hint=/path/to/jdk."
        exit 1
    fi

    if [ ! -x "$jdk_home/bin/java" ]; then
        log_error "Resolved JDK home for '$arch' is invalid: $jdk_home"
        log_info "Set $env_hint explicitly to a valid JDK 21 home."
        exit 1
    fi

    echo "$jdk_home"
}

reset_compose_runtime_cache() {
    rm -rf "$COMPOSE_RUNTIME_CACHE_DIR" "$COMPOSE_CHECK_RUNTIME_DIR"
}

cleanup() {
    log_info "Cleaning up..."
    # Keep the universal build directory for inspection
}

trap cleanup EXIT

# =============================================================================
# Load configuration
# =============================================================================

log_step "Loading configuration"

# Build number (passed as argument or default to 1)
BUILD_NUMBER="${1:-1}"

# Release edition (ru/en)
EDITION="${EDITION:-ru}"
if [ "$EDITION" != "ru" ] && [ "$EDITION" != "en" ]; then
    log_error "Unsupported edition '$EDITION'. Use EDITION=ru or EDITION=en."
    exit 1
fi

# JDK paths - environment/local.properties first, then fail-fast discovery
JDK_ARM64="${JDK_ARM64:-$(read_property 'macos.jdk.arm64' "$LOCAL_PROPERTIES")}"
JDK_X64="${JDK_X64:-$(read_property 'macos.jdk.x64' "$LOCAL_PROPERTIES")}"

if [ -z "$JDK_ARM64" ]; then
    JDK_ARM64="$(resolve_jdk_home arm64 JDK_ARM64)"
fi
if [ -z "$JDK_X64" ]; then
    JDK_X64="$(resolve_jdk_home x86_64 JDK_X64)"
fi

# Signing identities - from environment or local.properties
# These are the sertificates names (Common Name)
APP_SIGNING_IDENTITY="${MACOS_APP_SIGNING_IDENTITY:-$(read_property 'macos.signing.app.identity' "$LOCAL_PROPERTIES")}"
INSTALLER_SIGNING_IDENTITY="${MACOS_INSTALLER_SIGNING_IDENTITY:-$(read_property 'macos.signing.installer.identity' "$LOCAL_PROPERTIES")}"

# App name can be provided explicitly; otherwise detect from built .app
APP_NAME="${MACOS_APP_NAME:-}"

VERSION="${MACOS_APP_VERSION:-$(extract_gradle_value 'packageVersion')}"
VERSION="${VERSION:-1.0.0}"

# Entitlements and profiles
ENTITLEMENTS="$RESOURCES_DIR/entitlements.plist"
RUNTIME_ENTITLEMENTS="$RESOURCES_DIR/runtime-entitlements.plist"
APP_PROFILE="$RESOURCES_DIR/embedded.provisionprofile"
RUNTIME_PROFILE="$RESOURCES_DIR/runtime.provisionprofile"

# Output (resolved after APP_NAME is known for sure)
OUTPUT_PKG=""

# Display configuration
log_info "Configuration:"
log_info "  Edition: $EDITION"
log_info "  App Name: ${APP_NAME:-(auto-detect from built app bundle)}"
log_info "  Version: $VERSION"
log_info "  Build Number: $BUILD_NUMBER"
log_info "  JDK ARM64: $JDK_ARM64"
log_info "  JDK x64: $JDK_X64"
log_info "  App Signing Identity: ${APP_SIGNING_IDENTITY:-(not set)}"
log_info "  Installer Signing Identity: ${INSTALLER_SIGNING_IDENTITY:-(not set)}"

# =============================================================================
# Validation
# =============================================================================

log_step "Validating environment"

# Check signing identities are set
if [ -z "$APP_SIGNING_IDENTITY" ]; then
    log_error "App signing identity not set!"
    log_info "Set it in local.properties:"
    log_info "  macos.signing.app.identity=3rd Party Mac Developer Application: Your Name (TEAM_ID)"
    log_info "Or via environment variable:"
    log_info "  export MACOS_APP_SIGNING_IDENTITY=\"3rd Party Mac Developer Application: Your Name (TEAM_ID)\""
    exit 1
fi

if [ -z "$INSTALLER_SIGNING_IDENTITY" ]; then
    log_error "Installer signing identity not set!"
    log_info "Set it in local.properties:"
    log_info "  macos.signing.installer.identity=3rd Party Mac Developer Installer: Your Name (TEAM_ID)"
    log_info "Or via environment variable:"
    log_info "  export MACOS_INSTALLER_SIGNING_IDENTITY=\"3rd Party Mac Developer Installer: Your Name (TEAM_ID)\""
    exit 1
fi

# Check JDKs exist
if [ ! -d "$JDK_ARM64" ]; then
    log_error "ARM64 JDK not found at: $JDK_ARM64"
    log_info "Please install ARM64 JDK or set JDK_ARM64 environment variable"
    log_info "Or add to local.properties: macos.jdk.arm64=/path/to/jdk"
    exit 1
fi

if [ ! -d "$JDK_X64" ]; then
    log_error "x86_64 JDK not found at: $JDK_X64"
    log_info "Please install x86_64 JDK or set JDK_X64 environment variable"
    log_info "Or add to local.properties: macos.jdk.x64=/path/to/jdk"
    log_info "Download from: https://www.azul.com/downloads/?os=macos&architecture=x86-64-bit&package=jdk"
    exit 1
fi

# Verify JDK architectures
ARM64_ARCH=$("$JDK_ARM64/bin/java" -version 2>&1 | head -1)
X64_ARCH=$("$JDK_X64/bin/java" -version 2>&1 | head -1)
log_info "ARM64 JDK: $ARM64_ARCH"
log_info "x86_64 JDK: $X64_ARCH"

# Check signing certificates exist in keychain
if ! security find-identity -v | grep -q "$APP_SIGNING_IDENTITY"; then
    log_error "App signing certificate not found in keychain: $APP_SIGNING_IDENTITY"
    exit 1
fi

if ! security find-identity -v | grep -q "$INSTALLER_SIGNING_IDENTITY"; then
    log_error "Installer signing certificate not found in keychain: $INSTALLER_SIGNING_IDENTITY"
    exit 1
fi

# Check required files
for file in "$ENTITLEMENTS" "$RUNTIME_ENTITLEMENTS" "$APP_PROFILE" "$RUNTIME_PROFILE"; do
    if [ ! -f "$file" ]; then
        log_error "Required file not found: $file"
        exit 1
    fi
done

log_success "Environment validated"

# =============================================================================
# Clean build to ensure fresh compilation
# =============================================================================

log_step "Cleaning previous build artifacts"

cd "$PROJECT_DIR"

log_info "Running Gradle clean..."
./gradlew clean --quiet

log_success "Clean complete"

# =============================================================================
# Create build directory (AFTER clean to avoid deletion)
# =============================================================================

mkdir -p "$UNIVERSAL_BUILD_DIR"

# =============================================================================
# Prepare native JNI resources (ComposeApp conventions)
# =============================================================================

log_step "Preparing native JNI resources"

cd "$PROJECT_DIR"

# The compose app conventions plugin defines these tasks and wires them into
# resource/packaging tasks. Running them here makes failures explicit early.
log_info "Syncing TDLight/JNativeHook macOS JNI binaries..."
./gradlew \
    :composeApp:syncTdlightNativeMacosArm64 \
    :composeApp:syncTdlightNativeMacosX64 \
    :composeApp:syncJnativehookNativeMacosArm64 \
    :composeApp:syncJnativehookNativeMacosX64 \
    --quiet

# Verify expected files exist (as configured in ComposeAppConventionsPlugin)
TDLIGHT_ARM64="$GENERATED_NATIVE_RESOURCES_DIR/darwin-arm64/libtdjni.macos_arm64.dylib"
TDLIGHT_X64="$GENERATED_NATIVE_RESOURCES_DIR/darwin-x64/libtdjni.macos_amd64.dylib"
JNATIVEHOOK_ARM64="$GENERATED_NATIVE_RESOURCES_DIR/darwin-arm64/libJNativeHook.dylib"
JNATIVEHOOK_X64="$GENERATED_NATIVE_RESOURCES_DIR/darwin-x64/libJNativeHook.dylib"

for file in "$TDLIGHT_ARM64" "$TDLIGHT_X64" "$JNATIVEHOOK_ARM64" "$JNATIVEHOOK_X64"; do
    if [ ! -f "$file" ]; then
        log_error "Required native JNI resource not found: $file"
        exit 1
    fi
done

log_success "Native JNI resources are prepared"

# =============================================================================
# Build x86_64 version
# =============================================================================

log_step "Building x86_64 version"

cd "$PROJECT_DIR"

# Stop any running Gradle daemons
./gradlew --stop 2>/dev/null || true

# Clean previous builds
rm -rf "$BUILD_DIR"
log_info "Clearing cached Compose runtime image..."
reset_compose_runtime_cache

# Build with x86_64 JDK
log_info "Running Gradle build with x86_64 JDK..."
./gradlew :composeApp:createReleaseDistributable \
    -Pedition="$EDITION" \
    -Pmac.includeAllNativeResources=true \
    -PmacOsAppStoreRelease=true \
    -PbuildNumber="$BUILD_NUMBER" \
    -Dorg.gradle.java.home="$JDK_X64"

# Resolve built app path/name
X64_APP_DIR="$BUILD_DIR/main-release/app"
if [ ! -d "$X64_APP_DIR" ]; then
    log_error "x86_64 app directory not found at: $X64_APP_DIR"
    exit 1
fi

if [ -n "$APP_NAME" ]; then
    X64_APP="$X64_APP_DIR/${APP_NAME}.app"
else
    APP_BUNDLE_COUNT="$(find "$X64_APP_DIR" -maxdepth 1 -type d -name '*.app' | wc -l | tr -d ' ')"
    if [ "$APP_BUNDLE_COUNT" != "1" ]; then
        log_error "Cannot auto-detect app bundle name (found $APP_BUNDLE_COUNT bundles in $X64_APP_DIR)."
        log_info "Set MACOS_APP_NAME explicitly to the exact app bundle name."
        exit 1
    fi
    X64_APP="$(find "$X64_APP_DIR" -maxdepth 1 -type d -name '*.app' -print -quit)"
fi

if [ ! -d "$X64_APP" ]; then
    log_error "x86_64 app not found at: $X64_APP"
    exit 1
fi

DETECTED_APP_NAME="$(basename "$X64_APP" .app)"
if [ -n "$APP_NAME" ] && [ "$APP_NAME" != "$DETECTED_APP_NAME" ]; then
    log_error "Configured MACOS_APP_NAME ('$APP_NAME') does not match built app ('$DETECTED_APP_NAME')."
    exit 1
fi

APP_NAME="$DETECTED_APP_NAME"
OUTPUT_PKG="$PROJECT_DIR/composeApp/build/${APP_NAME}-${VERSION}-universal.pkg"
log_info "Resolved app bundle name: $APP_NAME"

if ! file "$X64_APP/Contents/MacOS/${APP_NAME}" | grep -q "x86_64"; then
    log_error "x86_64 build verification failed"
    exit 1
fi
if ! file "$X64_APP/Contents/runtime/Contents/Home/lib/libjli.dylib" | grep -q "x86_64"; then
    log_error "x86_64 runtime verification failed (libjli is not x86_64)"
    exit 1
fi

# Save x86_64 build
log_info "Saving x86_64 build..."
rm -rf "$UNIVERSAL_BUILD_DIR/${APP_NAME}-x86_64.app"
cp -R "$X64_APP" "$UNIVERSAL_BUILD_DIR/${APP_NAME}-x86_64.app"

log_success "x86_64 build complete"

# =============================================================================
# Build arm64 version
# =============================================================================

log_step "Building arm64 version"

# Stop daemon to switch JDK
./gradlew --stop

# Clean binaries directory
rm -rf "$BUILD_DIR"
log_info "Clearing cached Compose runtime image..."
reset_compose_runtime_cache

# Build with arm64 JDK
log_info "Running Gradle build with arm64 JDK..."
./gradlew :composeApp:createReleaseDistributable \
    -Pedition="$EDITION" \
    -Pmac.includeAllNativeResources=true \
    -PmacOsAppStoreRelease=true \
    -PbuildNumber="$BUILD_NUMBER" \
    -Dorg.gradle.java.home="$JDK_ARM64"

# Verify architecture
ARM64_APP="$BUILD_DIR/main-release/app/${APP_NAME}.app"
if [ ! -d "$ARM64_APP" ]; then
    log_error "arm64 app not found at: $ARM64_APP"
    exit 1
fi

if ! file "$ARM64_APP/Contents/MacOS/${APP_NAME}" | grep -q "arm64"; then
    log_error "arm64 build verification failed"
    exit 1
fi
if ! file "$ARM64_APP/Contents/runtime/Contents/Home/lib/libjli.dylib" | grep -q "arm64"; then
    log_error "arm64 runtime verification failed (libjli is not arm64)"
    exit 1
fi

log_success "arm64 build complete"

# =============================================================================
# Create universal binary
# =============================================================================

log_step "Creating universal binary"

UNIVERSAL_APP="$UNIVERSAL_BUILD_DIR/${APP_NAME}-universal.app"
X64_APP_SAVED="$UNIVERSAL_BUILD_DIR/${APP_NAME}-x86_64.app"

# Use arm64 as base
rm -rf "$UNIVERSAL_APP"
cp -R "$ARM64_APP" "$UNIVERSAL_APP"

# Find and combine all Mach-O binaries
log_info "Combining Mach-O binaries with lipo..."

find "$ARM64_APP" -type f \( -name "*.dylib" -o -perm +111 \) | while read arm64_file; do
    rel_path="${arm64_file#$ARM64_APP/}"
    x64_file="$X64_APP_SAVED/$rel_path"
    universal_file="$UNIVERSAL_APP/$rel_path"

    if file "$arm64_file" | grep -q "Mach-O"; then
        if [ -f "$x64_file" ]; then
            lipo -create "$arm64_file" "$x64_file" -output "$universal_file" 2>/dev/null && \
                echo "  Combined: $rel_path"
        fi
    fi
done

# Handle Skiko library (different names per architecture)
log_info "Handling Skiko libraries..."
SKIKO_X64="$X64_APP_SAVED/Contents/app/libskiko-macos-x64.dylib"
if [ -f "$SKIKO_X64" ]; then
    cp "$SKIKO_X64" "$UNIVERSAL_APP/Contents/app/"
    log_info "  Copied x64 Skiko library"
fi

# =============================================================================
# JNA native library is now bundled via appResourcesRootDir in build.gradle.kts
# =============================================================================
# The libjnidispatch.jnilib universal binary is in composeApp/resources/macos/
# and is automatically included and signed by the Compose Desktop plugin.
log_info "JNA native library: using pre-bundled universal binary from resources"

# Verify main executable is universal
if ! file "$UNIVERSAL_APP/Contents/MacOS/${APP_NAME}" | grep -q "universal"; then
    log_error "Universal binary creation failed"
    exit 1
fi

log_success "Universal binary created"

# =============================================================================
# Remove quarantine attributes
# =============================================================================

log_step "Removing quarantine attributes"

log_info "Removing com.apple.quarantine from all files..."
xattr -dr com.apple.quarantine "$UNIVERSAL_APP" 2>/dev/null || true
log_success "Quarantine attributes removed"

# =============================================================================
# Sign the application
# =============================================================================

log_step "Signing application"

# Embed provisioning profiles (and remove quarantine from them)
log_info "Embedding provisioning profiles..."
cp "$APP_PROFILE" "$UNIVERSAL_APP/Contents/embedded.provisionprofile"
cp "$RUNTIME_PROFILE" "$UNIVERSAL_APP/Contents/runtime/Contents/embedded.provisionprofile"
xattr -cr "$UNIVERSAL_APP/Contents/embedded.provisionprofile" 2>/dev/null || true
xattr -cr "$UNIVERSAL_APP/Contents/runtime/Contents/embedded.provisionprofile" 2>/dev/null || true

# Sign runtime components
log_info "Signing runtime libraries..."
find "$UNIVERSAL_APP/Contents/runtime" -type f \( -name "*.dylib" -o -perm +111 \) | while read file; do
    if file "$file" | grep -q "Mach-O"; then
        codesign --force --options runtime --timestamp \
            --entitlements "$RUNTIME_ENTITLEMENTS" \
            --sign "$APP_SIGNING_IDENTITY" \
            "$file" 2>/dev/null
    fi
done

# Sign Skiko libraries
log_info "Signing Skiko libraries..."
for skiko in "$UNIVERSAL_APP/Contents/app/libskiko"*.dylib; do
    if [ -f "$skiko" ]; then
        codesign --force --options runtime --timestamp \
            --entitlements "$RUNTIME_ENTITLEMENTS" \
            --sign "$APP_SIGNING_IDENTITY" \
            "$skiko"
    fi
done

# Sign JNA native library (must be signed after combining builds)
log_info "Signing JNA native library..."
JNA_LIB="$UNIVERSAL_APP/Contents/app/resources/libjnidispatch.jnilib"
if [ -f "$JNA_LIB" ]; then
    codesign --force --options runtime --timestamp \
        --entitlements "$RUNTIME_ENTITLEMENTS" \
        --sign "$APP_SIGNING_IDENTITY" \
        "$JNA_LIB"
    log_info "  Signed: libjnidispatch.jnilib"
else
    log_error "JNA native library not found at: $JNA_LIB"
    exit 1
fi

# Sign runtime bundle
log_info "Signing runtime bundle..."
codesign --force --options runtime --timestamp \
    --entitlements "$RUNTIME_ENTITLEMENTS" \
    --sign "$APP_SIGNING_IDENTITY" \
    "$UNIVERSAL_APP/Contents/runtime"

# Sign main executable
log_info "Signing main executable..."
codesign --force --options runtime --timestamp \
    --entitlements "$ENTITLEMENTS" \
    --sign "$APP_SIGNING_IDENTITY" \
    "$UNIVERSAL_APP/Contents/MacOS/${APP_NAME}"

# Sign app bundle
log_info "Signing app bundle..."
codesign --force --options runtime --timestamp \
    --entitlements "$ENTITLEMENTS" \
    --sign "$APP_SIGNING_IDENTITY" \
    "$UNIVERSAL_APP"

# Verify signature
log_info "Verifying signature..."
if ! codesign --verify --deep --strict "$UNIVERSAL_APP"; then
    log_error "Signature verification failed"
    exit 1
fi

log_success "Application signed and verified"

# =============================================================================
# Final quarantine cleanup
# =============================================================================

log_info "Final quarantine attribute cleanup..."
xattr -cr "$UNIVERSAL_APP" 2>/dev/null || true

# =============================================================================
# Create installer package
# =============================================================================

log_step "Creating installer package"

log_info "Building pkg..."
productbuild --component "$UNIVERSAL_APP" /Applications \
    --sign "$INSTALLER_SIGNING_IDENTITY" \
    "$OUTPUT_PKG"

# Verify package
log_info "Verifying package signature..."
pkgutil --check-signature "$OUTPUT_PKG"

log_success "Package created: $OUTPUT_PKG"

# =============================================================================
# Summary
# =============================================================================

log_step "Build complete!"

echo ""
echo "Universal macOS App Store package:"
echo "  $OUTPUT_PKG"
echo ""
echo "Package size: $(du -h "$OUTPUT_PKG" | cut -f1)"
echo "App name: $APP_NAME"
echo "Version: $VERSION"
echo "Build number: $BUILD_NUMBER"
echo ""
echo "Architectures included:"
echo "  - x86_64 (Intel)"
echo "  - arm64 (Apple Silicon)"
echo ""
echo "Intermediate builds saved in:"
echo "  $UNIVERSAL_BUILD_DIR"
echo ""
echo "Next steps:"
echo "  1. Open Transporter app"
echo "  2. Drag and drop the .pkg file"
echo "  3. Click 'Deliver'"
echo ""
