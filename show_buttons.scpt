#!/usr/bin/osascript
use AppleScript version "2.7"
use scripting additions

on collectButtonsFrom(el, depth, btnInfos)
    if depth > 4 then return btnInfos -- stop deep recursion
    tell application "System Events"
        try
            tell el to set {r, n, d, h, p, s} to {role, name, description, help, position, size}
            if r is "AXButton" then
                if n is missing value or n = "" then
                    if d is not missing value and d ≠ "" then
                        set n to d
                    else if h is not missing value and h ≠ "" then
                        set n to h
                    else
                        set n to "[untitled button]"
                    end if
                end if
                set btnInfos to btnInfos & {n & " @ (" & item 1 of p & "," & item 2 of p & ") " & item 1 of s & "x" & item 2 of s}
            end if
            repeat with child in (UI elements of el)
                set btnInfos to my collectButtonsFrom(child, depth + 1, btnInfos)
            end repeat
        end try
    end tell
    return btnInfos
end collectButtonsFrom

tell application "System Events"
    set frontProc to first application process whose frontmost is true
    set btnInfos to my collectButtonsFrom(front window of frontProc, 0, {})
end tell

if btnInfos = {} then
    display dialog "No buttons found." buttons {"OK"}
else
    set text item delimiters to linefeed
    display dialog (btnInfos as text) buttons {"OK"} default button 1
end if
