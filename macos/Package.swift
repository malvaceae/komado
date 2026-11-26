// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KomadoCore",
    platforms: [.macOS(.v13)],
    products: [.library(name: "KomadoCore", targets: ["KomadoCore"])],
    dependencies: [
        .package(url: "https://github.com/apple/swift-certificates.git", exact: "1.21.2"),
    ],
    targets: [
        .target(name: "KomadoCore", dependencies: [.product(name: "X509", package: "swift-certificates")]),
        .testTarget(name: "KomadoCoreTests", dependencies: ["KomadoCore"]),
    ]
)
