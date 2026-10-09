//
//  ContentView.swift
//  PencilProbe
//
//  Created by Ethan Meng on 9/14/26.
//

import SwiftUI

struct ContentView: View {
    var body: some View {
        PencilCanvasRepresentable()
            .ignoresSafeArea()
    }
}

#Preview {
    ContentView()
}
