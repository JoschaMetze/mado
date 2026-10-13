import AppKit
import Foundation
import ServiceManagement

/// Everything the menu shows and everything the app does: polls the enabled players,
/// tells the hub what is playing, and carries taps on the frame back to the player.
@MainActor
final class Model: ObservableObject {
    let sources: [MusicSource] = [AppleMusic(), Spotify()]

    @Published var hubAddress: String {
        didSet {
            defaults.set(hubAddress, forKey: "hub")
            last = nil
            listenForCommands()
        }
    }
    @Published var sharing: Bool {
        didSet {
            defaults.set(sharing, forKey: "sharing")
            last = nil
        }
    }
    @Published private var disabled: Set<String>
    @Published private(set) var statusLine = "Starting…"
    @Published private(set) var playingLine = "Nothing playing"
    @Published private(set) var reachable = false
    @Published private(set) var startsAtLogin = SMAppService.mainApp.status == .enabled

    private let defaults = UserDefaults.standard
    private let machine = Host.current().localizedName ?? "Mac"
    private var active: MusicSource?
    private var artKey = ""
    private var art: String?
    private var sendArt = false
    private var last: Report?
    private var busy = false
    private var timer: Timer?
    private var commands: Task<Void, Never>?

    init() {
        hubAddress = defaults.string(forKey: "hub") ?? ""
        sharing = defaults.object(forKey: "sharing") as? Bool ?? true
        disabled = Set(defaults.stringArray(forKey: "disabledSources") ?? [])
        // Hosted unit tests launch the app; it must not poll players or call a hub then.
        guard ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        listenForCommands()
        tick()
    }

    var hub: Hub? { Hub.url(from: hubAddress).map(Hub.init) }

    func isEnabled(_ source: MusicSource) -> Bool { !disabled.contains(source.id) }

    /// A player that is not on this Mac is never asked: naming a missing app in AppleScript
    /// makes macOS ask the user where it is.
    func isInstalled(_ source: MusicSource) -> Bool {
        NSWorkspace.shared.urlForApplication(withBundleIdentifier: source.bundleID) != nil
    }

    func setEnabled(_ source: MusicSource, _ on: Bool) {
        if on { disabled.remove(source.id) } else { disabled.insert(source.id) }
        defaults.set(Array(disabled), forKey: "disabledSources")
        last = nil
    }

    /// The first enabled player that is playing, otherwise the first that has a track at all.
    private func current() async -> (MusicSource, Track)? {
        guard sharing else { return nil }
        var reads: [(MusicSource, Track)] = []
        for source in sources where isEnabled(source) && isInstalled(source) {
            if let track = await source.read() { reads.append((source, track)) }
        }
        return reads.first { $0.1.playing } ?? reads.first
    }

    private func tick() {
        guard hub != nil else {
            statusLine = "Set the hub address to begin"
            reachable = false
            return
        }
        // One round at a time. A round can take a while: the first read of a player waits
        // for the user to answer the permission prompt.
        guard !busy else { return }
        busy = true
        Task {
            await round()
            busy = false
        }
    }

    private func round() async {
        let now = await current()
        guard let hub else { return }
        let track = now?.1
        active = now?.0
        let state = track.map { $0.playing ? "playing" : "paused" } ?? "stopped"
        let key = track?.key ?? ""
        playingLine = track.map { "\($0.title) · \($0.artist)" } ?? (sharing ? "Nothing playing" : "Sharing is paused")

        if key != artKey {
            artKey = key
            art = nil
            sendArt = false
            if let (source, track) = now {
                Task {
                    let data = await source.artwork(for: track)
                    // The listener may have skipped on while the cover was being fetched.
                    if let data, self.artKey == key {
                        self.art = data.base64EncodedString()
                        self.sendArt = true
                    }
                }
            }
        }

        let position = track?.position ?? 0
        guard sendArt || Report.isDue(last: last, state: state, key: key, position: position, now: Date()) else { return }
        var body: [String: Any] = ["state": state, "source": machine, "position_s": position]
        if let track {
            body["title"] = track.title
            body["artist"] = track.artist
            body["album"] = track.album
            body["duration_s"] = track.duration
        }
        let withArt = sendArt
        if withArt { body["art_b64"] = art }

        do {
            let reply = try await hub.post("api/now_playing", body)
            // A restarted hub has lost the cover; hand it over again next round. If it
            // was in this very request, the hub could not read it and never will.
            sendArt = !withArt && art != nil && (reply["have_art"] as? Bool) == false
            last = Report(state: state, key: key, position: position, at: Date())
            reachable = true
            statusLine = "Connected to \(hub.base.host ?? "the hub")"
        } catch {
            last = nil
            sendArt = art != nil
            reachable = false
            statusLine = "Cannot reach the hub"
        }
    }

    /// Taps on the frame (play or pause, next, previous) arrive through a long poll and go to whichever
    /// player was last reported.
    private func listenForCommands() {
        commands?.cancel()
        commands = Task {
            var seq: Int?
            while !Task.isCancelled {
                guard let hub else {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    continue
                }
                do {
                    let reply = try await hub.nextCommand(after: seq)
                    seq = reply["seq"] as? Int
                    if let name = reply["command"] as? String, let command = MediaCommand(rawValue: name), sharing {
                        await active?.send(command)
                        // Tell the frame what came of it without waiting for the next round.
                        last = nil
                        tick()
                    }
                } catch {
                    // Start over from the hub's current number so an old tap is not replayed.
                    seq = nil
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                }
            }
        }
    }

    func setStartsAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            alert("Could not change the login setting", error.localizedDescription)
        }
        startsAtLogin = SMAppService.mainApp.status == .enabled
    }

    func askForHubAddress() {
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24))
        field.stringValue = hubAddress
        field.placeholderString = "http://hub.local:7878"
        let box = NSAlert()
        box.messageText = "Where is your Mado hub?"
        box.informativeText = "The address of the hub's web page, for example http://192.168.1.20:7878"
        box.accessoryView = field
        box.addButton(withTitle: "Save")
        box.addButton(withTitle: "Cancel")
        box.window.initialFirstResponder = field
        NSApp.activate(ignoringOtherApps: true)
        guard box.runModal() == .alertFirstButtonReturn else { return }
        if Hub.url(from: field.stringValue) == nil, !field.stringValue.isEmpty {
            alert("That does not look like an address", "Try something like http://192.168.1.20:7878")
        } else {
            hubAddress = field.stringValue
        }
    }

    func openSettings() {
        if let url = hub?.base { NSWorkspace.shared.open(url) }
    }

    private func alert(_ title: String, _ detail: String) {
        let box = NSAlert()
        box.messageText = title
        box.informativeText = detail
        NSApp.activate(ignoringOtherApps: true)
        box.runModal()
    }
}
