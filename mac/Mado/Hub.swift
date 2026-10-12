import Foundation

/// The hub's HTTP API, as far as this app uses it.
struct Hub {
    let base: URL

    /// Accepts what people type: "nas:7878", "http://10.0.0.5:7878/", a full https URL.
    static func url(from typed: String) -> URL? {
        var text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.contains("://") { text = "http://" + text }
        while text.hasSuffix("/") { text.removeLast() }
        guard let url = URL(string: text), url.host != nil, ["http", "https"].contains(url.scheme) else { return nil }
        return url
    }

    func post(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.timeoutInterval = 15
        return try await send(request)
    }

    /// Long poll for a tap on the frame. Pass nil once to learn the current sequence number.
    func nextCommand(after seq: Int?) async throws -> [String: Any] {
        var parts = URLComponents(url: base.appendingPathComponent("api/now_playing/commands"), resolvingAgainstBaseURL: false)!
        if let seq { parts.queryItems = [URLQueryItem(name: "after", value: String(seq))] }
        var request = URLRequest(url: parts.url!)
        request.timeoutInterval = 40
        return try await send(request)
    }

    private func send(_ request: URLRequest) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}

/// Decides when the hub needs telling again.
struct Report: Equatable {
    var state: String
    var key: String
    var position: Double
    var at: Date

    /// The hub treats a silent sender as stopped after 30 s, so repeat well inside that.
    static let heartbeat: TimeInterval = 10
    /// A position this far from where steady playback would be means the listener scrubbed.
    static let scrub: Double = 2.5

    static func isDue(last: Report?, state: String, key: String, position: Double, now: Date) -> Bool {
        guard let last else { return true }
        let elapsed = now.timeIntervalSince(last.at)
        let expected = last.position + (last.state == "playing" ? elapsed : 0)
        return last.state != state || last.key != key || elapsed >= heartbeat || abs(position - expected) > scrub
    }
}
