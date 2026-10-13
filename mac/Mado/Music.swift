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
protocol MusicSource: AnyObject, Sendable {
    /// Stable key for settings.
    var id: String { get }
    var name: String { get }
    /// The player's bundle identifier, used to tell whether it is installed.
    var bundleID: String { get }
    /// nil when the player is closed or stopped. Must never launch the player.
    func read() async -> Track?
    /// The cover for `track`, which is the one this source just reported.
    func artwork(for track: Track) async -> Data?
    func send(_ command: MediaCommand) async
}

/// Runs AppleScript through `osascript`, off the main thread. macOS asks the user once per
/// player for permission, and attributes the request to this app.
///
/// Not NSAppleScript: that is main-thread only and spins the run loop while it waits for the
/// player to answer, which lets SwiftUI re-enter itself and crash.
enum OSA {
    static func run(_ script: String, _ arguments: [String] = []) async -> String? {
        await withCheckedContinuation { done in
            DispatchQueue.global(qos: .utility).async {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
                process.arguments = ["-e", script] + arguments
                let output = Pipe()
                process.standardOutput = output
                process.standardError = FileHandle.nullDevice
                guard (try? process.run()) != nil else { return done.resume(returning: nil) }
                let data = output.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                guard process.terminationStatus == 0 else { return done.resume(returning: nil) }
                var text = String(decoding: data, as: UTF8.self)
                if text.hasSuffix("\n") { text.removeLast() }
                done.resume(returning: text)
            }
        }
    }

    /// What to tell `player` to do for a tap on the frame. Never launches the player.
    /// "Previous" does what the button in a player does: back to the start of the track,
    /// or to the track before when this one has only just begun.
    static func script(for command: MediaCommand, player: String) -> String {
        let action: String
        switch command {
        case .playpause: action = "playpause"
        case .next: action = "next track"
        case .previous: action = "if player position > \(restartAfter) then set player position to 0 else previous track"
        }
        return "if application \"\(player)\" is running then tell application \"\(player)\" to \(action)"
    }

    /// Seconds into a track after which "previous" restarts it.
    static let restartAfter = 3
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

final class AppleMusic: MusicSource {
    let id = "apple-music"
    let name = "Apple Music"
    let bundleID = "com.apple.Music"

    func read() async -> Track? {
        PlayerReply.parse(await OSA.run("""
            if application "Music" is not running then return "stopped"
            tell application "Music"
                set s to player state as text
                if s is "stopped" then return "stopped"
                set d to character id 31
                set t to current track
                return s & d & (name of t) & d & (artist of t) & d & (album of t) & d & (duration of t) & d & (player position)
            end tell
            """))
    }

    /// Music's own artwork if it has any; streamed tracks often have none, and then the
    /// cover is looked up in the iTunes catalogue.
    func artwork(for track: Track) async -> Data? {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("mado-artwork-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: file) }
        let wrote = await OSA.run("""
            on run argv
                if application "Music" is not running then return "none"
                tell application "Music"
                    try
                        set bytes to raw data of artwork 1 of current track
                    on error
                        return "none"
                    end try
                end tell
                set f to open for access POSIX file (item 1 of argv) with write permission
                set eof f to 0
                write bytes to f
                close access f
                return "ok"
            end run
            """, [file.path])
        if wrote == "ok", let data = try? Data(contentsOf: file), !data.isEmpty { return data }
        return await Catalogue.artwork(for: track)
    }

    func send(_ command: MediaCommand) async {
        _ = await OSA.run(OSA.script(for: command, player: "Music"))
    }
}

final class Spotify: MusicSource {
    let id = "spotify"
    let name = "Spotify"
    let bundleID = "com.spotify.client"

    // Spotify reports duration in milliseconds and gives a URL for the cover.
    func read() async -> Track? {
        PlayerReply.parse(await OSA.run("""
            if application "Spotify" is not running then return "stopped"
            tell application "Spotify"
                set s to player state as text
                if s is "stopped" then return "stopped"
                set d to character id 31
                set t to current track
                return s & d & (name of t) & d & (artist of t) & d & (album of t) & d & (duration of t) & d & (player position) & d & (artwork url of t)
            end tell
            """), durationScale: 0.001)
    }

    func artwork(for track: Track) async -> Data? {
        if let url = track.artworkURL, let (data, _) = try? await URLSession.shared.data(from: url), !data.isEmpty {
            return data
        }
        return await Catalogue.artwork(for: track)
    }

    func send(_ command: MediaCommand) async {
        _ = await OSA.run(OSA.script(for: command, player: "Spotify"))
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
