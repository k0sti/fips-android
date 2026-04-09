{
  description = "FIPS Android app development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    rust-overlay.url = "github:oxalica/rust-overlay";
    rust-overlay.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, rust-overlay, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        overlays = [ rust-overlay.overlays.default ];
        config.android_sdk.accept_license = true;
        config.allowUnfree = true;
      };

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        buildToolsVersions = [ "34.0.0" "35.0.0" ];
        platformVersions = [ "35" ];
        ndkVersions = [ "26.1.10909125" ];
        includeNDK = true;
        includeSources = false;
        includeSystemImages = false;
        includeEmulator = false;
      };

      androidSdk = androidComposition.androidsdk;
      androidNdk = "${androidSdk}/libexec/android-sdk/ndk/26.1.10909125";

      rustToolchain = pkgs.rust-bin.stable.latest.default.override {
        targets = [
          "aarch64-linux-android"
          "armv7-linux-androideabi"
          "x86_64-linux-android"
        ];
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = [
          rustToolchain
          pkgs.cargo-ndk
          androidSdk
          pkgs.jdk17
          pkgs.gradle
        ];

        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        ANDROID_NDK_HOME = androidNdk;
        JAVA_HOME = "${pkgs.jdk17}";

        shellHook = ''
          echo "FIPS Android dev environment ready"
          echo "  Rust: $(rustc --version)"
          echo "  NDK:  $ANDROID_NDK_HOME"
          echo "  JDK:  $JAVA_HOME"
        '';
      };
    };
}
