# Show available recipes
default:
    @just --list

android_dir := justfile_directory() / "android"
apk := android_dir / "app/build/outputs/apk/debug/app-debug.apk"
package := "space.atlantislabs.fips"
jni_dir := android_dir / "app/src/main/jniLibs/arm64-v8a"
bindings_dir := android_dir / "app/src/main/java"
ndk_target := "aarch64-linux-android"
so_name := "libfips_mobile.so"

# Cross-compile fips-mobile for Android arm64
ndk-build:
    cargo ndk -t arm64-v8a build -p fips-mobile --release

# Generate Kotlin bindings from compiled .so
bindings: ndk-build
    cargo run -p uniffi-bindgen -- generate \
        --library target/{{ndk_target}}/release/{{so_name}} \
        --language kotlin \
        --out-dir {{bindings_dir}}

# Copy native library to Android jniLibs
libs: ndk-build
    mkdir -p {{jni_dir}}
    cp target/{{ndk_target}}/release/{{so_name}} {{jni_dir}}/

# Build debug APK (run ndk-build + bindings + libs first)
build: bindings libs
    cd {{android_dir}} && ./gradlew assembleDebug

# Clean all build artifacts (Gradle + Cargo)
clean:
    cd {{android_dir}} && ./gradlew clean
    cargo clean
    rm -rf {{jni_dir}}
    rm -rf {{bindings_dir}}/uniffi

# Install debug APK on connected device (build + install, no launch)
install: build
    adb -d install -r {{apk}}

# Install and run on connected device
device: build
    adb -d install -r {{apk}}
    adb -d shell am start -n {{package}}/.MainActivity

# Grab latest debug dump from device logcat
status:
    adb -d logcat -d -s "FipsDump:*" | tail -30

# Build, install, run autotest (start node, wait for peers, dump, exit)
test:
    @cargo ndk -t arm64-v8a build -p fips-mobile --release 2>&1 | tail -1
    @cargo run -p uniffi-bindgen -- generate \
        --library target/{{ndk_target}}/release/{{so_name}} \
        --language kotlin \
        --out-dir {{bindings_dir}} > /dev/null 2>&1
    @mkdir -p {{jni_dir}}
    @cp target/{{ndk_target}}/release/{{so_name}} {{jni_dir}}/
    @cd {{android_dir}} && ./gradlew assembleDebug -q
    @just _test-run

# Run autotest only (skip build, assumes APK already installed)
test-quick: _test-run

_test-run:
    adb -d install -r {{apk}}
    adb -d logcat --clear
    @echo "Starting autotest..."
    @adb -d shell am start -n {{package}}/.MainActivity --ez autotest true > /dev/null
    @adb -d logcat -s "FipsAutotest:*" -e "AUTOTEST DONE" -m 1 > /dev/null
    @adb -d logcat -d -s "FipsAutotest:*" | sed 's/^.*FipsAutotest: //'

# Install, run on device, and tail logcat
debug: build
    adb -d install -r {{apk}}
    adb -d logcat --clear
    adb -d shell am start -n {{package}}/.MainActivity
    adb -d logcat -s "FipsViewModel:*" "fips_mobile:*" "fips:*" "space.atlantislabs.fips:*" "AndroidRuntime:*" "System.err:*"
