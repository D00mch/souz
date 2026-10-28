# Release shrinking

macOS release tasks use Compose's ProGuard integration to remove unused classes and members from `androidx.compose.material.icons`. Optimization, obfuscation, and JAR joining are disabled. Debug builds and release builds on other operating systems do not use ProGuard.

[The rules](../proguard-rules.pro) retain all classes and members outside the icon catalog, including reflection, Jackson, ServiceLoader, JNA, and JNI entry points. All class attributes are retained. Missing-reference warnings are suppressed outside the icon catalog because retained dependencies include optional integrations absent from the desktop classpath. Expanding shrinking beyond icons requires auditing those integrations and revisiting that warning filter.

Keep rules alone do not preserve multi-release entries and duplicate resources across dependency JARs. After ProGuard runs, the build restores every runtime JAR outside the icon catalog byte for byte, including the desktop and shared modules. Only the two Material icon JARs use transformed output. JVM preverification remains enabled to generate valid stack frames in transformed icons.

Compose owns the prepared release classpath. Packaging finalizers preserve it while preparing native resources, patching runtime privacy descriptions, and signing.

Run:

```sh
./gradlew :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg
./gradlew :desktopApp:verifyMacReleaseShrinking
```

The [artifact tests](../src/test/kotlin/ru/souz/packaging/MacReleaseShrinkingTest.kt) inspect the prepared and packaged classpaths. Prepared non-icon JARs must match the inputs byte for byte, including native resources. Packaged entry digests include manifests and multi-release classes while ignoring rewritten ZIP headers and macOS native signing/extraction outputs. Tests also preserve class entries and service descriptors, check application bytecode's icon references, construct retained icons with JVM verification and an isolated classloader, and verify the deep code signature. Ordinary unit tests do not run these packaging tests. Removed classes and members are reported in `build/compose/tmp/proguardReleaseJars/removed.txt` under `desktopApp`.

The packaging test task is excluded from Kover instrumentation and report dependencies. The JVM coverage graph does not package or launch a desktop release.

Measure baseline and candidate app logical file bytes and DMG bytes in the same checkout with identical toolchain, architecture, signing mode, native-resource settings, and packaging flags. Keep a baseline copy outside Compose's output directory before rebuilding. Confirm the packaged app through LaunchServices with an isolated home and preferences; `user.home` alone does not isolate macOS Java Preferences. Exercise local models, document tools, voice, and native integrations as well as the main window and icons. See [release packaging and signing](../../docs/release.md).
