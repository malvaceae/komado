import XCTest
@testable import KomadoHost

final class MediaFactoryTests: XCTestCase {
    func testFactoryProvidesVideoSenderCapabilities() {
        let factory = MediaFactory.create()
        XCTAssertFalse(factory.rtpSenderCapabilities(forKind: "video").codecs.isEmpty)
    }
}
