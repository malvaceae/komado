// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KomadoCore",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "KomadoCore", targets: ["KomadoCore"]),
        .executable(name: "KomadoHost", targets: ["KomadoHost"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-certificates.git", exact: "1.21.2"),
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "158.0.0"),
    ],
    targets: [
        .target(name: "KomadoCore", dependencies: [.product(name: "X509", package: "swift-certificates")]),
        .executableTarget(name: "KomadoHost", dependencies: ["KomadoCore", .product(name: "WebRTC", package: "WebRTC")],
                          linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"])]),
        .testTarget(name: "KomadoCoreTests", dependencies: ["KomadoCore"]),
        .testTarget(name: "KomadoHostTests", dependencies: ["KomadoHost", "KomadoCore"]),
    ]
)
