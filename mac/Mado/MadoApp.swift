import SwiftUI

@main
struct MadoApp: App {
    @StateObject private var model = Model()

    var body: some Scene {
        MenuBarExtra {
            MadoMenu(model: model)
        } label: {
            // A frame; struck through when the hub cannot be reached.
            Image(systemName: model.reachable ? "photo.artframe" : "rectangle.slash")
        }
        .menuBarExtraStyle(.menu)
    }
}

struct MadoMenu: View {
    @ObservedObject var model: Model

    var body: some View {
        Text(model.statusLine)
        Text(model.playingLine)
        Divider()
        Toggle("Share What’s Playing", isOn: $model.sharing)
        ForEach(model.sources, id: \.id) { source in
            Toggle(source.name, isOn: Binding(
                get: { model.isEnabled(source) },
                set: { model.setEnabled(source, $0) }
            ))
        }
        Divider()
        Button("Open Mado Settings") { model.openSettings() }
            .disabled(model.hub == nil)
        Button("Hub Address…") { model.askForHubAddress() }
        Toggle("Start at Login", isOn: Binding(
            get: { model.startsAtLogin },
            set: { model.setStartsAtLogin($0) }
        ))
        Divider()
        Button("Quit Mado") { NSApplication.shared.terminate(nil) }
            .keyboardShortcut("q")
    }
}
