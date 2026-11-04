import AppKit
import Foundation

// An original geometric app mark; generated build asset, no third-party artwork.
let output = CommandLine.arguments[1]
let canvas = NSImage(size: NSSize(width: 1024, height: 1024))
canvas.lockFocus()
NSColor(calibratedRed: 0.13, green: 0.33, blue: 0.68, alpha: 1).setFill()
NSBezierPath(rect: NSRect(x: 0, y: 0, width: 1024, height: 1024)).fill()
NSColor.white.setFill()
let book = NSBezierPath(roundedRect: NSRect(x: 240, y: 210, width: 544, height: 604), xRadius: 52, yRadius: 52)
book.fill()
NSColor(calibratedRed: 0.13, green: 0.33, blue: 0.68, alpha: 1).setFill()
for y in [610, 488, 366] { NSBezierPath(roundedRect: NSRect(x: 340, y: CGFloat(y), width: 344, height: 40), xRadius: 20, yRadius: 20).fill() }
canvas.unlockFocus()
let rep = NSBitmapImageRep(data: canvas.tiffRepresentation!)!
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
