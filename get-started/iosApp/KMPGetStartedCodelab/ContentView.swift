//
//  ContentView.swift
//  KMPGetStartedCodelab
//
//

import SwiftUI
import foundationKit
import businessKit

struct ContentView: View {
    var body: some View {
        VStack {
            Image(systemName: "globe")
                .imageScale(.large)
                .foregroundStyle(.tint)
            Text("Hello, \(Platform_iosKt.platform())!")

            let userService = UserService()
            let tag = userService.formatUserTag(user: userService.currentUser())
            Text("User: \(tag)")
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
