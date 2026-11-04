// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CampusReaderCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CampusReaderCore", targets: ["CampusReaderCore"])],
    dependencies: [.package(url: "https://github.com/scinfu/SwiftSoup.git", exact: "2.9.6")],
    targets: [
        .target(name: "CampusReaderCore", dependencies: [.product(name: "SwiftSoup", package: "SwiftSoup")]),
        .testTarget(name: "CampusReaderCoreTests", dependencies: ["CampusReaderCore"])
    ]
)
