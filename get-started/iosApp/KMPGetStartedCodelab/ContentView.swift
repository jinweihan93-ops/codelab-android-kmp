//
//  ContentView.swift
//  KMPGetStartedCodelab
//
//

import SwiftUI
import foundationKit

struct ContentView: View {
    var body: some View {
        VStack {
            Image(systemName: "globe")
                .imageScale(.large)
                .foregroundStyle(.tint)
            Text("Hello, \(Platform_iosKt.platform())!")
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
