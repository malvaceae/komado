// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "KomadoCore",
    platforms: [.macOS(.v13)],
    products: [.library(name: "KomadoCore", targets: ["KomadoCore"])],
    targets: [
        .target(name: "KomadoCore"),
        .testTarget(name: "KomadoCoreTests", dependencies: ["KomadoCore"]),
    ]
)
