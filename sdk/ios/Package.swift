// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DevLens",
    platforms: [.iOS(.v14), .macOS(.v12)],
    products: [
        .library(name: "ProbeCore", targets: ["ProbeCore"]),
        .library(name: "ProbeNetwork", targets: ["ProbeNetwork"]),
    ],
    targets: [
        .target(
            name: "ProbeCore",
            dependencies: [],
            path: "Sources/ProbeCore"
        ),
        .target(
            name: "ProbeNetwork",
            dependencies: ["ProbeCore"],
            path: "Sources/ProbeNetwork"
        ),
    ]
)
