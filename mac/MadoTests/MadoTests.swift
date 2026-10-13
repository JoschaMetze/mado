import XCTest
@testable import Mado

final class MadoTests: XCTestCase {
    private let d = PlayerReply.separator

    func testParsesAPlayingTrack() {
        let track = PlayerReply.parse(["playing", "So What", "Miles Davis", "Kind of Blue", "562.0", "42.5"].joined(separator: d))
        XCTAssertEqual(track, Track(playing: true, title: "So What", artist: "Miles Davis", album: "Kind of Blue", duration: 562, position: 42.5))
    }

    func testParsesDecimalCommasAndMilliseconds() {
        let reply = ["paused", "Song", "Artist", "Album", "241500", "12,5", "https://example.com/cover.jpg"].joined(separator: d)
        let track = PlayerReply.parse(reply, durationScale: 0.001)
        XCTAssertEqual(track?.playing, false)
        XCTAssertEqual(track?.duration ?? 0, 241.5, accuracy: 0.001)
        XCTAssertEqual(track?.position, 12.5)
        XCTAssertEqual(track?.artworkURL?.host, "example.com")
    }

    func testCommandsNeverLaunchThePlayer() {
        for command in [MediaCommand.playpause, .next, .previous] {
            XCTAssertTrue(OSA.script(for: command, player: "Music").hasPrefix("if application \"Music\" is running then "))
        }
    }

    func testPreviousRestartsATrackThatIsUnderWay() {
        let script = OSA.script(for: .previous, player: "Spotify")
        XCTAssertTrue(script.hasSuffix("tell application \"Spotify\" to if player position > 3 then set player position to 0 else previous track"))
    }

    func testStoppedOrMissingIsNoTrack() {
        XCTAssertNil(PlayerReply.parse("stopped"))
        XCTAssertNil(PlayerReply.parse(nil))
    }

    func testTrackKeyIgnoresPositionAndState() {
        var a = Track(playing: true, title: "T", artist: "A", album: "L", duration: 10, position: 1)
        let key = a.key
        a.position = 9
        a.playing = false
        XCTAssertEqual(a.key, key)
    }

    func testHubAddressAcceptsWhatPeopleType() {
        XCTAssertEqual(Hub.url(from: " nas:7878/ ")?.absoluteString, "http://nas:7878")
        XCTAssertEqual(Hub.url(from: "https://mado.example.com")?.absoluteString, "https://mado.example.com")
        XCTAssertNil(Hub.url(from: ""))
        XCTAssertNil(Hub.url(from: "ftp://nope"))
    }

    func testReportIsDueOnChangeHeartbeatAndScrub() {
        let t0 = Date(timeIntervalSince1970: 1000)
        let last = Report(state: "playing", key: "k", position: 10, at: t0)
        func due(_ state: String, _ key: String, _ position: Double, after seconds: Double) -> Bool {
            Report.isDue(last: last, state: state, key: key, position: position, now: t0.addingTimeInterval(seconds))
        }
        XCTAssertTrue(Report.isDue(last: nil, state: "stopped", key: "", position: 0, now: t0))
        XCTAssertFalse(due("playing", "k", 13, after: 3), "steady playback needs no report")
        XCTAssertTrue(due("paused", "k", 13, after: 3))
        XCTAssertTrue(due("playing", "other", 0, after: 3))
        XCTAssertTrue(due("playing", "k", 60, after: 3), "a scrub is reported at once")
        XCTAssertTrue(due("playing", "k", 20, after: 10), "heartbeat")
    }
}
