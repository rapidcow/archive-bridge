//
//  PencilCanvasView.swift
//  PencilProbe
//
//  Created by Ethan Meng on 9/14/26.
//

import SwiftUI
import UIKit

/// A UIKit canvas that reports Apple Pencil azimuth/altitude/force the same
/// way UITouch/UIHoverGestureRecognizer expose them, so readings here can be
/// compared directly against values decoded from NoteStore.sqlite.
final class PencilCanvasView: UIView {

    private var isPenDown = false
    private var cursorLocation: CGPoint?
    private var hasReading = false

    private var azimuthAngle: CGFloat = 0
    private var azimuthUnitVector: CGVector = .zero
    private var altitudeAngle: CGFloat = 0
    private var forceFraction: CGFloat = 0
    private var sourceDescription = "none"

    private let cursorRadius: CGFloat = 12
    private let diagnosticsLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setUp()
    }

    private func setUp() {
        backgroundColor = .systemBackground
        isMultipleTouchEnabled = false

        diagnosticsLabel.numberOfLines = 0
        diagnosticsLabel.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
        diagnosticsLabel.textColor = .label
        diagnosticsLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(diagnosticsLabel)
        NSLayoutConstraint.activate([
            diagnosticsLabel.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 8),
            diagnosticsLabel.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor, constant: 8),
        ])

        addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(handleHover(_:))))

        updateDiagnosticsText()
    }

    // MARK: - Touch (pen in contact with the glass)

    // NOTE: not filtered to touch.type == .pencil — on the Simulator every
    // click/drag arrives as .direct (finger-like), so filtering to pencil
    // only would mean nothing ever renders there. We accept any touch and
    // just label what kind it was, so the on-screen circle/label can be
    // sanity-checked without real Pencil hardware. azimuth/altitude/force
    // are only *meaningful* when source reads "pencil" — for anything else
    // UIKit returns fixed constants (altitude = pi/2, azimuth = 0), which is
    // expected, not a bug.

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        isPenDown = true
        capture(from: touch)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        capture(from: touch)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        isPenDown = false
        capture(from: touch)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        isPenDown = false
        capture(from: touch)
    }

    private func capture(from touch: UITouch) {
        cursorLocation = touch.location(in: self)
        azimuthAngle = touch.azimuthAngle(in: self)
        azimuthUnitVector = touch.azimuthUnitVector(in: self)
        altitudeAngle = touch.altitudeAngle
        forceFraction = touch.maximumPossibleForce > 0 ? touch.force / touch.maximumPossibleForce : 0
        sourceDescription = describe(touch.type)
        hasReading = true
        refresh()
    }

    private func describe(_ type: UITouch.TouchType) -> String {
        switch type {
        case .pencil: return "pencil"
        case .direct: return "finger/direct"
        case .indirect: return "indirect"
        case .indirectPointer: return "mouse/trackpad"
        @unknown default: return "unknown"
        }
    }

    // MARK: - Hover (pen in proximity, iPad Pro M2+/Pencil Pro, iPadOS 16.1+)

    @objc private func handleHover(_ recognizer: UIHoverGestureRecognizer) {
        switch recognizer.state {
        case .began, .changed:
            isPenDown = false
            cursorLocation = recognizer.location(in: self)
            azimuthAngle = recognizer.azimuthAngle(in: self)
            azimuthUnitVector = recognizer.azimuthUnitVector(in: self)
            altitudeAngle = recognizer.altitudeAngle
            forceFraction = 0
            sourceDescription = "hover"
            hasReading = true
        case .ended, .cancelled:
            cursorLocation = nil
        default:
            break
        }
        refresh()
    }

    // MARK: - Rendering

    private func refresh() {
        setNeedsDisplay()
        updateDiagnosticsText()
    }

    private func updateDiagnosticsText() {
        guard hasReading else {
            diagnosticsLabel.text = "source    —\nazimuth   —\naltitude  —\nforce     —"
            return
        }
        let note = sourceDescription == "pencil" ? "" : "  (not pencil — angles are fixed defaults)"
        diagnosticsLabel.text = String(
            format: "source    %@%@\nazimuth   %+.4f rad  (x=%+.4f, y=%+.4f)\naltitude  %+.4f rad\nforce     %5.1f%%",
            sourceDescription, note,
            azimuthAngle, azimuthUnitVector.dx, azimuthUnitVector.dy,
            altitudeAngle,
            forceFraction * 100
        )
    }

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard let location = cursorLocation, let ctx = UIGraphicsGetCurrentContext() else { return }

        let circleRect = CGRect(
            x: location.x - cursorRadius, y: location.y - cursorRadius,
            width: cursorRadius * 2, height: cursorRadius * 2
        )
        ctx.setStrokeColor(UIColor.systemBlue.cgColor)
        ctx.setLineWidth(2)
        if isPenDown {
            ctx.setFillColor(UIColor.systemBlue.withAlphaComponent(0.6).cgColor)
            ctx.fillEllipse(in: circleRect)
        }
        ctx.strokeEllipse(in: circleRect)
    }
}

struct PencilCanvasRepresentable: UIViewRepresentable {
    func makeUIView(context: Context) -> PencilCanvasView {
        PencilCanvasView()
    }

    func updateUIView(_ uiView: PencilCanvasView, context: Context) {}
}
