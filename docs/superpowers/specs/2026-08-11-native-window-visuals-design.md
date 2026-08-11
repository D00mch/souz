# Native window visuals

## Goal

Make the desktop window feel consistent with native macOS surfaces without changing layout or interaction behavior.

## Design

- Use an opaque `#1E1E1E` dark window surface and a subtle `#F0F0F0` to `#ECECEC` light gradient.
- Use `#242424` for dark panels and `#E8E8E8` for the light settings sidebar.
- Draw one 1 dp inner window outline: 14% white in dark mode and 12% black in light mode.
- Use 8% foreground color for panel dividers in both themes.
- Use a 12 dp radius only for the shared outer window container. Component-specific radii remain unchanged.
- Ask macOS to render the native window shadow. Other platforms keep their current behavior.

## Implementation boundaries

- Keep visual tokens in the existing theme palette.
- Simplify the shared glass container from two border strokes to one inner stroke.
- Extend the existing macOS window adapter instead of adding a new shadow abstraction.
- Do not change ViewModels, business logic, navigation, or persistence.

## Verification

- Check exact palette values with a focused JVM test during development.
- Run the shared UI theme tests and affected module builds.
- Launch the desktop application and inspect both themes on macOS.
