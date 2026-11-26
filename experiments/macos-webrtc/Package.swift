// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WebRTCProbe",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "158.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "WebRTCProbe",
            dependencies: [.product(name: "WebRTC", package: "WebRTC")],
            // SwiftPMの実行ファイルと同じディレクトリに置かれるフレームワークを読む。
            linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path"])]
        ),
    ]
)
