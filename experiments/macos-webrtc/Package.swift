// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KomadoExperiments",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "158.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "ExchangeProbe",
            dependencies: [.product(name: "WebRTC", package: "WebRTC")],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path"])]
        ),
        .executableTarget(
            name: "CaptureProbe",
            dependencies: [.product(name: "WebRTC", package: "WebRTC")],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path"])]
        ),
    ]
)
