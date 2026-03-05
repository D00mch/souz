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

set -Eeuo pipefail  # Exit on error; fail on unset vars and pipeline errors

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

on_error() {
    local exit_code="$?"
    local line_no="${BASH_LINENO[0]:-unknown}"
    log_error "Build failed (exit $exit_code) at line $line_no: $BASH_COMMAND"
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

is_truthy() {
    local normalized
    normalized="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
        1|true|yes|y|on) return 0 ;;
        0|false|no|n|off|"") return 1 ;;
        *)
            log_error "Invalid boolean value: '$1'"
            log_info "Use true/false, yes/no, 1/0."
            exit 1
            ;;
    esac
}

codesign_sign() {
    local entitlements="$1"
    local target="$2"
    local output
    if [ -n "${SIGNING_KEYCHAIN:-}" ]; then
        if ! output="$(
            codesign --force --options runtime "$CODESIGN_TIMESTAMP_FLAG" \
                --keychain "$SIGNING_KEYCHAIN" \
                --entitlements "$entitlements" \
                --sign "$APP_SIGNING_IDENTITY" \
                "$target" 2>&1
        )"; then
            printf '%s\n' "$output" >&2
            if printf '%s' "$output" | grep -q "errSecInternalComponent"; then
                log_info "codesign returned errSecInternalComponent for: $target"
                log_info "This usually means keychain access or certificate chain problems."
                log_info "Ensure Apple WWDR intermediate cert is installed and trusted."
                log_info "If using a non-default keychain, set MACOS_SIGNING_KEYCHAIN and MACOS_SIGNING_KEYCHAIN_PASSWORD."
            fi
            return 1
        fi
    elif ! output="$(
        codesign --force --options runtime "$CODESIGN_TIMESTAMP_FLAG" \
            --entitlements "$entitlements" \
            --sign "$APP_SIGNING_IDENTITY" \
            "$target" 2>&1
    )"; then
        printf '%s\n' "$output" >&2
        if printf '%s' "$output" | grep -q "errSecInternalComponent"; then
            log_info "codesign returned errSecInternalComponent for: $target"
            log_info "This usually means keychain access or certificate chain problems."
            log_info "Ensure Apple WWDR intermediate cert is installed and trusted."
            log_info "If using a non-default keychain, set MACOS_SIGNING_KEYCHAIN and MACOS_SIGNING_KEYCHAIN_PASSWORD."
        fi
        return 1
    fi

    if [ -n "$output" ]; then
        printf '%s\n' "$output"
    fi
}

mach_o_archs() {
    local target="$1"
    lipo -archs "$target" 2>/dev/null || true
}

has_arch_overlap() {
    local left_archs="$1"
    local right_archs="$2"
    local left_arch
    local right_arch

    for left_arch in $left_archs; do
        for right_arch in $right_archs; do
            if [ "$left_arch" = "$right_arch" ]; then
                return 0
            fi
        done
    done
    return 1
}

identity_exists_in_keychain() {
    local identity="$1"
    local keychain="$2"
    local policy="${3:-codesigning}"
    security find-identity -v -p "$policy" "$keychain" 2>/dev/null | grep -Fq "$identity"
}

identity_exists_global() {
    local identity="$1"
    local policy="${2:-codesigning}"
    security find-identity -v -p "$policy" 2>/dev/null | grep -Fq "$identity"
}

extract_signing_cert_to_file() {
    local identity="$1"
    local keychain="$2"
    local out_file="$3"

    if [ -n "$keychain" ]; then
        security find-certificate -a -p -c "$identity" "$keychain" > "$out_file" 2>/dev/null || true
    else
        security find-certificate -a -p -c "$identity" > "$out_file" 2>/dev/null || true
    fi
}

certificate_common_name_exists() {
    local common_name="$1"
    local keychain="$2"
    security find-certificate -a -c "$common_name" "$keychain" 2>/dev/null | grep -q "alis"
}

reset_compose_runtime_cache() {
    rm -rf "$COMPOSE_RUNTIME_CACHE_DIR" "$COMPOSE_CHECK_RUNTIME_DIR"
}

cleanup() {
    log_info "Cleaning up..."
    rm -f "${APP_PROFILE_PLIST_TMP:-}" \
        "${RUNTIME_PROFILE_PLIST_TMP:-}" \
        "${APP_SIGN_ENTITLEMENTS_TMP:-}" \
        "${RUNTIME_SIGN_ENTITLEMENTS_TMP:-}" \
        "${RUNTIME_COMPONENT_ENTITLEMENTS_TMP:-}"
    # Keep the universal build directory for inspection
}

trap on_error ERR
trap cleanup EXIT

decode_provision_profile() {
    local profile_path="$1"
    local output_plist="$2"
    if ! openssl smime -inform der -verify -noverify -in "$profile_path" -out "$output_plist" >/dev/null 2>&1; then
        log_error "Failed to decode provisioning profile: $profile_path"
        return 1
    fi
}

profile_entitlement_value() {
    local profile_plist="$1"
    local key="$2"
    /usr/libexec/PlistBuddy -c "Print :Entitlements:$key" "$profile_plist" 2>/dev/null || true
}

prepare_signing_entitlements() {
    local base_entitlements="$1"
    local profile_plist="$2"
    local output_entitlements="$3"
    local app_id
    local team_id

    cp "$base_entitlements" "$output_entitlements"

    app_id="$(profile_entitlement_value "$profile_plist" "com.apple.application-identifier")"
    team_id="$(profile_entitlement_value "$profile_plist" "com.apple.developer.team-identifier")"

    if [ -z "$app_id" ] || [ -z "$team_id" ]; then
        log_error "Provisioning profile entitlements are missing required identifiers."
        log_info "  app_id: ${app_id:-(missing)}"
        log_info "  team_id: ${team_id:-(missing)}"
        return 1
    fi

    /usr/libexec/PlistBuddy -c "Delete :com.apple.application-identifier" "$output_entitlements" >/dev/null 2>&1 || true
    /usr/libexec/PlistBuddy -c "Delete :com.apple.developer.team-identifier" "$output_entitlements" >/dev/null 2>&1 || true
    /usr/libexec/PlistBuddy -c "Add :com.apple.application-identifier string $app_id" "$output_entitlements"
    /usr/libexec/PlistBuddy -c "Add :com.apple.developer.team-identifier string $team_id" "$output_entitlements"
}

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
SIGNING_KEYCHAIN="${MACOS_SIGNING_KEYCHAIN:-}"
SIGNING_KEYCHAIN_PASSWORD="${MACOS_SIGNING_KEYCHAIN_PASSWORD:-}"
INSTALLER_SIGNING_KEYCHAIN="${MACOS_INSTALLER_SIGNING_KEYCHAIN:-}"
INSTALLER_SIGNING_KEYCHAIN_PASSWORD="${MACOS_INSTALLER_SIGNING_KEYCHAIN_PASSWORD:-}"

# For App Store certificates timestamping is optional. Keep it off by default
# to avoid network-dependent signing failures.
MACOS_CODESIGN_TIMESTAMP="${MACOS_CODESIGN_TIMESTAMP:-false}"
if is_truthy "$MACOS_CODESIGN_TIMESTAMP"; then
    CODESIGN_TIMESTAMP_FLAG="--timestamp"
else
    CODESIGN_TIMESTAMP_FLAG="--timestamp=none"
fi

if [ -n "$SIGNING_KEYCHAIN" ]; then
    if [ ! -e "$SIGNING_KEYCHAIN" ]; then
        log_error "Signing keychain not found: $SIGNING_KEYCHAIN"
        exit 1
    fi
fi
if [ -n "$INSTALLER_SIGNING_KEYCHAIN" ]; then
    if [ ! -e "$INSTALLER_SIGNING_KEYCHAIN" ]; then
        log_error "Installer signing keychain not found: $INSTALLER_SIGNING_KEYCHAIN"
        exit 1
    fi
fi

# App name can be provided explicitly; otherwise detect from built .app
APP_NAME="${MACOS_APP_NAME:-}"

VERSION="${MACOS_APP_VERSION:-$(extract_gradle_value 'packageVersion')}"
VERSION="${VERSION:-1.0.0}"

# Entitlements and profiles
ENTITLEMENTS="$RESOURCES_DIR/entitlements.plist"
RUNTIME_ENTITLEMENTS="$RESOURCES_DIR/runtime-entitlements.plist"
APP_PROFILE="$RESOURCES_DIR/embedded.provisionprofile"
RUNTIME_PROFILE="$RESOURCES_DIR/runtime.provisionprofile"
APP_PROFILE_PLIST_TMP=""
RUNTIME_PROFILE_PLIST_TMP=""
APP_SIGN_ENTITLEMENTS_TMP=""
RUNTIME_SIGN_ENTITLEMENTS_TMP=""
RUNTIME_COMPONENT_ENTITLEMENTS_TMP=""

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
log_info "  Code Signing Timestamp: $CODESIGN_TIMESTAMP_FLAG"
if [ -n "$SIGNING_KEYCHAIN" ]; then
    log_info "  Signing Keychain: $SIGNING_KEYCHAIN"
fi
if [ -n "$INSTALLER_SIGNING_KEYCHAIN" ]; then
    log_info "  Installer Signing Keychain: $INSTALLER_SIGNING_KEYCHAIN"
fi

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
if [ -n "$SIGNING_KEYCHAIN" ] && [ -n "$SIGNING_KEYCHAIN_PASSWORD" ]; then
    log_info "Unlocking signing keychain..."
    security unlock-keychain -p "$SIGNING_KEYCHAIN_PASSWORD" "$SIGNING_KEYCHAIN"
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$SIGNING_KEYCHAIN_PASSWORD" "$SIGNING_KEYCHAIN" >/dev/null
fi
if [ -n "$INSTALLER_SIGNING_KEYCHAIN" ] && [ -n "$INSTALLER_SIGNING_KEYCHAIN_PASSWORD" ]; then
    if [ "$INSTALLER_SIGNING_KEYCHAIN" != "$SIGNING_KEYCHAIN" ]; then
        log_info "Unlocking installer signing keychain..."
        security unlock-keychain -p "$INSTALLER_SIGNING_KEYCHAIN_PASSWORD" "$INSTALLER_SIGNING_KEYCHAIN"
    fi
fi

if [ -n "$SIGNING_KEYCHAIN" ]; then
    if ! identity_exists_in_keychain "$APP_SIGNING_IDENTITY" "$SIGNING_KEYCHAIN" codesigning; then
        log_error "App signing certificate not found in keychain: $APP_SIGNING_IDENTITY"
        log_info "Checked keychain: $SIGNING_KEYCHAIN"
        exit 1
    fi
else
    if ! identity_exists_global "$APP_SIGNING_IDENTITY" codesigning; then
        log_error "App signing certificate not found in keychain: $APP_SIGNING_IDENTITY"
        exit 1
    fi
fi

if [ -n "$INSTALLER_SIGNING_KEYCHAIN" ]; then
    if ! identity_exists_in_keychain "$INSTALLER_SIGNING_IDENTITY" "$INSTALLER_SIGNING_KEYCHAIN" basic \
        && ! identity_exists_in_keychain "$INSTALLER_SIGNING_IDENTITY" "$INSTALLER_SIGNING_KEYCHAIN" codesigning; then
        log_error "Installer signing certificate not found in keychain: $INSTALLER_SIGNING_IDENTITY"
        log_info "Checked keychain: $INSTALLER_SIGNING_KEYCHAIN"
        exit 1
    fi
else
    if [ -n "$SIGNING_KEYCHAIN" ] && {
        identity_exists_in_keychain "$INSTALLER_SIGNING_IDENTITY" "$SIGNING_KEYCHAIN" basic \
            || identity_exists_in_keychain "$INSTALLER_SIGNING_IDENTITY" "$SIGNING_KEYCHAIN" codesigning
    }; then
        :
    elif ! identity_exists_global "$INSTALLER_SIGNING_IDENTITY" basic \
        && ! identity_exists_global "$INSTALLER_SIGNING_IDENTITY" codesigning; then
        log_error "Installer signing certificate not found in keychain: $INSTALLER_SIGNING_IDENTITY"
        if [ -n "$SIGNING_KEYCHAIN" ]; then
            log_info "Checked keychains: $SIGNING_KEYCHAIN and default keychain search list"
            log_info "Set MACOS_INSTALLER_SIGNING_KEYCHAIN if installer cert lives in a separate keychain."
        fi
        log_info "Run 'security find-identity -v -p basic' to inspect installer identities."
        exit 1
    fi
fi

if ! command -v openssl >/dev/null 2>&1; then
    log_error "openssl is required for signing certificate chain validation."
    exit 1
fi

APP_CERT_TMP="$(mktemp /tmp/souz-app-signing-cert.XXXXXX.pem)"
extract_signing_cert_to_file "$APP_SIGNING_IDENTITY" "$SIGNING_KEYCHAIN" "$APP_CERT_TMP"
if [ ! -s "$APP_CERT_TMP" ]; then
    log_error "Unable to export app signing certificate for identity: $APP_SIGNING_IDENTITY"
    if [ -n "$SIGNING_KEYCHAIN" ]; then
        log_info "Checked keychain: $SIGNING_KEYCHAIN"
    fi
    rm -f "$APP_CERT_TMP"
    exit 1
fi

APP_CERT_SUBJECT="$(openssl x509 -in "$APP_CERT_TMP" -noout -subject 2>/dev/null | sed 's/^subject=//')"
APP_CERT_ISSUER="$(openssl x509 -in "$APP_CERT_TMP" -noout -issuer 2>/dev/null | sed 's/^issuer=//')"
APP_CERT_ISSUER_CN="$(openssl x509 -in "$APP_CERT_TMP" -noout -issuer -nameopt RFC2253 2>/dev/null | sed -E 's/^issuer=//; s/.*CN=([^,]+).*/\1/')"
if [ -n "$APP_CERT_SUBJECT" ]; then
    log_info "App signing cert subject:$APP_CERT_SUBJECT"
fi
if [ -n "$APP_CERT_ISSUER" ]; then
    log_info "App signing cert issuer:$APP_CERT_ISSUER"
fi

ISSUER_SEARCH_KEYCHAINS=()
if [ -e "$HOME/Library/Keychains/login.keychain-db" ]; then
    ISSUER_SEARCH_KEYCHAINS+=("$HOME/Library/Keychains/login.keychain-db")
fi
if [ -e "/Library/Keychains/System.keychain" ]; then
    ISSUER_SEARCH_KEYCHAINS+=("/Library/Keychains/System.keychain")
fi
if [ -n "$SIGNING_KEYCHAIN" ]; then
    ISSUER_SEARCH_KEYCHAINS+=("$SIGNING_KEYCHAIN")
fi
if [ -n "$INSTALLER_SIGNING_KEYCHAIN" ] && [ "$INSTALLER_SIGNING_KEYCHAIN" != "$SIGNING_KEYCHAIN" ]; then
    ISSUER_SEARCH_KEYCHAINS+=("$INSTALLER_SIGNING_KEYCHAIN")
fi

if [ -n "$APP_CERT_ISSUER_CN" ]; then
    issuer_found=0
    for keychain in "${ISSUER_SEARCH_KEYCHAINS[@]}"; do
        if certificate_common_name_exists "$APP_CERT_ISSUER_CN" "$keychain"; then
            issuer_found=1
            break
        fi
    done
    if [ "$issuer_found" -eq 0 ]; then
        log_error "Required intermediate certificate is missing from local keychains: $APP_CERT_ISSUER_CN"
        log_info "Install this Apple intermediate certificate in login/System keychain and set trust to system defaults."
        log_info "Apple certificate downloads: https://www.apple.com/certificateauthority/"
        rm -f "$APP_CERT_TMP"
        exit 1
    fi
fi

if ! security verify-cert -p basic -c "$APP_CERT_TMP" >/dev/null 2>&1; then
    log_error "App signing certificate chain validation failed."
    if [ -n "$APP_CERT_ISSUER" ]; then
        log_info "Missing/untrusted intermediate likely: $APP_CERT_ISSUER"
    fi
    log_info "Install required Apple intermediate certificates from: https://www.apple.com/certificateauthority/"
    rm -f "$APP_CERT_TMP"
    exit 1
fi
rm -f "$APP_CERT_TMP"

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

combined_count=0
skipped_overlap_count=0

while IFS= read -r -d '' arm64_file; do
    rel_path="${arm64_file#$ARM64_APP/}"
    x64_file="$X64_APP_SAVED/$rel_path"
    universal_file="$UNIVERSAL_APP/$rel_path"

    if file "$arm64_file" | grep -q "Mach-O"; then
        if [ -f "$x64_file" ]; then
            arm64_archs="$(mach_o_archs "$arm64_file")"
            x64_archs="$(mach_o_archs "$x64_file")"

            if [ -z "$arm64_archs" ] || [ -z "$x64_archs" ]; then
                log_error "Unable to detect Mach-O architectures for: $rel_path"
                log_info "  arm64 source: $arm64_file"
                log_info "  x64 source: $x64_file"
                exit 1
            fi

            if has_arch_overlap "$arm64_archs" "$x64_archs"; then
                skipped_overlap_count=$((skipped_overlap_count + 1))
                echo "  Skipped (overlapping archs: $rel_path)"
                continue
            fi

            lipo -create "$arm64_file" "$x64_file" -output "$universal_file"
            combined_count=$((combined_count + 1))
            echo "  Combined: $rel_path"
        fi
    fi
done < <(find "$ARM64_APP" -type f \( -name "*.dylib" -o -perm +111 \) -print0)

log_info "Lipo summary: combined $combined_count file(s), skipped $skipped_overlap_count file(s) with overlapping architectures"

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

# Build effective signing entitlements by merging required identifiers from
# provisioning profiles into base entitlements.
APP_PROFILE_PLIST_TMP="$(mktemp /tmp/souz-app-profile.XXXXXX.plist)"
RUNTIME_PROFILE_PLIST_TMP="$(mktemp /tmp/souz-runtime-profile.XXXXXX.plist)"
APP_SIGN_ENTITLEMENTS_TMP="$(mktemp /tmp/souz-app-entitlements.XXXXXX.plist)"
RUNTIME_SIGN_ENTITLEMENTS_TMP="$(mktemp /tmp/souz-runtime-entitlements.XXXXXX.plist)"
RUNTIME_COMPONENT_ENTITLEMENTS_TMP="$(mktemp /tmp/souz-runtime-components-entitlements.XXXXXX.plist)"

decode_provision_profile "$APP_PROFILE" "$APP_PROFILE_PLIST_TMP"
decode_provision_profile "$RUNTIME_PROFILE" "$RUNTIME_PROFILE_PLIST_TMP"
prepare_signing_entitlements "$ENTITLEMENTS" "$APP_PROFILE_PLIST_TMP" "$APP_SIGN_ENTITLEMENTS_TMP"
prepare_signing_entitlements "$RUNTIME_ENTITLEMENTS" "$RUNTIME_PROFILE_PLIST_TMP" "$RUNTIME_SIGN_ENTITLEMENTS_TMP"
cp "$RUNTIME_ENTITLEMENTS" "$RUNTIME_COMPONENT_ENTITLEMENTS_TMP"

log_info "App signing entitlement com.apple.application-identifier: $(profile_entitlement_value "$APP_PROFILE_PLIST_TMP" "com.apple.application-identifier")"
log_info "Runtime signing entitlement com.apple.application-identifier: $(profile_entitlement_value "$RUNTIME_PROFILE_PLIST_TMP" "com.apple.application-identifier")"

# Sign runtime components
log_info "Signing runtime libraries..."
while IFS= read -r -d '' file; do
    if file "$file" | grep -q "Mach-O"; then
        codesign_sign "$RUNTIME_COMPONENT_ENTITLEMENTS_TMP" "$file"
    fi
done < <(find "$UNIVERSAL_APP/Contents/runtime" -type f \( -name "*.dylib" -o -perm +111 \) -print0)

# Sign Skiko libraries
log_info "Signing Skiko libraries..."
for skiko in "$UNIVERSAL_APP/Contents/app/libskiko"*.dylib; do
    if [ -f "$skiko" ]; then
        codesign_sign "$RUNTIME_COMPONENT_ENTITLEMENTS_TMP" "$skiko"
    fi
done

# Sign bundled JNA native library if present (path depends on JNA packaging mode).
log_info "Signing JNA native library (if bundled)..."
JNA_SIGNED_COUNT=0
while IFS= read -r -d '' jna_lib; do
    codesign_sign "$RUNTIME_COMPONENT_ENTITLEMENTS_TMP" "$jna_lib"
    JNA_SIGNED_COUNT=$((JNA_SIGNED_COUNT + 1))
    log_info "  Signed: ${jna_lib#$UNIVERSAL_APP/}"
done < <(find "$UNIVERSAL_APP" -type f \( -name 'libjnidispatch*.jnilib' -o -name 'libjnidispatch*.dylib' \) -print0)

if [ "$JNA_SIGNED_COUNT" -eq 0 ]; then
    log_info "  No bundled libjnidispatch binary found; JNA will load native library from jar/runtime path."
fi

# Sign JNI/JNA native libraries bundled under app resources.
# These binaries are loaded at runtime from Contents/app/resources/darwin-*.
log_info "Signing bundled app resource native libraries..."
RESOURCE_NATIVE_SIGNED_COUNT=0
while IFS= read -r -d '' resource_native_lib; do
    if file "$resource_native_lib" | grep -q "Mach-O"; then
        codesign_sign "$RUNTIME_COMPONENT_ENTITLEMENTS_TMP" "$resource_native_lib"
        RESOURCE_NATIVE_SIGNED_COUNT=$((RESOURCE_NATIVE_SIGNED_COUNT + 1))
        log_info "  Signed: ${resource_native_lib#$UNIVERSAL_APP/}"
    fi
done < <(find "$UNIVERSAL_APP/Contents/app/resources" -type f \( -name "*.dylib" -o -name "*.jnilib" \) -print0)

if [ "$RESOURCE_NATIVE_SIGNED_COUNT" -eq 0 ]; then
    log_info "  No native libraries found in Contents/app/resources."
fi

# Sign runtime bundle
log_info "Signing runtime bundle..."
codesign_sign "$RUNTIME_SIGN_ENTITLEMENTS_TMP" "$UNIVERSAL_APP/Contents/runtime"

# Sign main executable
log_info "Signing main executable..."
codesign_sign "$APP_SIGN_ENTITLEMENTS_TMP" "$UNIVERSAL_APP/Contents/MacOS/${APP_NAME}"

# Sign app bundle
log_info "Signing app bundle..."
codesign_sign "$APP_SIGN_ENTITLEMENTS_TMP" "$UNIVERSAL_APP"

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
if [ -n "$INSTALLER_SIGNING_KEYCHAIN" ]; then
    productbuild --component "$UNIVERSAL_APP" /Applications \
        --sign "$INSTALLER_SIGNING_IDENTITY" \
        --keychain "$INSTALLER_SIGNING_KEYCHAIN" \
        "$OUTPUT_PKG"
else
    productbuild --component "$UNIVERSAL_APP" /Applications \
        --sign "$INSTALLER_SIGNING_IDENTITY" \
        "$OUTPUT_PKG"
fi

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
