import Foundation

/// What a player is doing right now.
struct Track: Equatable {
    var playing: Bool
    var title: String
    var artist: String
    var album: String
    var duration: Double
    var position: Double
    /// Where the player says the cover can be downloaded, if it says.
    var artworkURL: URL?

    /// Identifies the track for artwork purposes; position and play state do not change it.
    var key: String { [title, artist, album].joined(separator: "\u{1f}") }
}

enum MediaCommand: String {
    case playpause, next, previous
}

/// A player on this Mac that Mado can report on.
@MainActor
protocol MusicSource: AnyObject {
    /// Stable key for settings.
    var id: String { get }
    var name: String { get }
    /// nil when the player is closed or stopped. Must never launch the player.
    func read() -> Track?
    /// The cover for `track`, which is the one this source just reported.
    func artwork(for track: Track) async -> Data?
    func send(_ command: MediaCommand)
}

/// Runs AppleScript in-process. macOS asks the user once per player for permission.
/// NSAppleScript is main-thread only; each call takes a few milliseconds.
@MainActor
final class Script {
    private let source: String
    private var compiled: NSAppleScript?

    init(_ source: String) {
        self.source = source
    }

    /// A script that names an app's own terms only compiles while that app is installed,
    /// so a failed compile is retried later instead of being remembered.
    func run() -> NSAppleEventDescriptor? {
        var error: NSDictionary?
        if compiled == nil, let script = NSAppleScript(source: source), script.compileAndReturnError(&error) {
            compiled = script
        }
        return compiled?.executeAndReturnError(&error)
    }
}

enum PlayerReply {
    static let separator = "\u{1f}"

    /// Parses "state, title, artist, album, duration, position[, artwork url]" joined by the
    /// separator. `durationScale` turns the player's duration unit into seconds.
    static func parse(_ reply: String?, durationScale: Double = 1) -> Track? {
        guard let fields = reply?.components(separatedBy: separator), fields.count >= 6 else { return nil }
        // AppleScript writes numbers in the system's locale, which may use a decimal comma.
        func number(_ s: String) -> Double { Double(s.replacingOccurrences(of: ",", with: ".")) ?? 0 }
        return Track(
            playing: fields[0] != "paused",
            title: fields[1], artist: fields[2], album: fields[3],
            duration: number(fields[4]) * durationScale, position: number(fields[5]),
            artworkURL: fields.count > 6 ? URL(string: fields[6]) : nil
        )
    }
}

@MainActor
final class AppleMusic: MusicSource {
    let id = "apple-music"
    let name = "Apple Music"

    private let reader = Script("""
        if application "Music" is not running then return "stopped"
        tell application "Music"
            set s to player state as text
            if s is "stopped" then return "stopped"
            set d to character id 31
            set t to current track
            return s & d & (name of t) & d & (artist of t) & d & (album of t) & d & (duration of t) & d & (player position)
        end tell
        """)

    private let cover = Script("""
        if application "Music" is not running then return missing value
        tell application "Music" to return raw data of artwork 1 of current track
        """)

    func read() -> Track? {
        PlayerReply.parse(reader.run()?.stringValue)
    }

    /// Music's own artwork if it has any; streamed tracks often have none, and then the
    /// cover is looked up in the iTunes catalogue.
    func artwork(for track: Track) async -> Data? {
        if let data = cover.run()?.data, !data.isEmpty { return data }
        return await Catalogue.artwork(for: track)
    }

    func send(_ command: MediaCommand) {
        let verb: String
        switch command {
        case .playpause: verb = "playpause"
        case .next: verb = "next track"
        case .previous: verb = "previous track"
        }
        _ = Script("if application \"Music\" is running then tell application \"Music\" to \(verb)").run()
    }
}

@MainActor
final class Spotify: MusicSource {
    let id = "spotify"
    let name = "Spotify"

    // Spotify reports duration in milliseconds and gives a URL for the cover.
    private let reader = Script("""
        if application "Spotify" is not running then return "stopped"
        tell application "Spotify"
            set s to player state as text
            if s is "stopped" then return "stopped"
            set d to character id 31
            set t to current track
            return s & d & (name of t) & d & (artist of t) & d & (album of t) & d & (duration of t) & d & (player position) & d & (artwork url of t)
        end tell
        """)

    func read() -> Track? {
        PlayerReply.parse(reader.run()?.stringValue, durationScale: 0.001)
    }

    func artwork(for track: Track) async -> Data? {
        if let url = track.artworkURL, let (data, _) = try? await URLSession.shared.data(from: url), !data.isEmpty {
            return data
        }
        return await Catalogue.artwork(for: track)
    }

    func send(_ command: MediaCommand) {
        let verb: String
        switch command {
        case .playpause: verb = "playpause"
        case .next: verb = "next track"
        case .previous: verb = "previous track"
        }
        _ = Script("if application \"Spotify\" is running then tell application \"Spotify\" to \(verb)").run()
    }
}

/// Covers looked up in the iTunes catalogue by artist and album.
enum Catalogue {
    static func artwork(for track: Track) async -> Data? {
        var search = URLComponents(string: "https://itunes.apple.com/search")!
        search.queryItems = [
            URLQueryItem(name: "term", value: "\(track.artist) \(track.album)"),
            URLQueryItem(name: "entity", value: "album"),
            URLQueryItem(name: "limit", value: "5"),
        ]
        guard let url = search.url,
              let (found, _) = try? await URLSession.shared.data(from: url),
              let json = try? JSONSerialization.jsonObject(with: found) as? [String: Any],
              let results = json["results"] as? [[String: Any]], !results.isEmpty
        else { return nil }
        let album = track.album.lowercased()
        let best = results.first { ($0["collectionName"] as? String)?.lowercased() == album } ?? results[0]
        guard let small = best["artworkUrl100"] as? String,
              let large = URL(string: small.replacingOccurrences(of: "100x100bb", with: "1000x1000bb")),
              let (image, _) = try? await URLSession.shared.data(from: large)
        else { return nil }
        return image
    }
}
