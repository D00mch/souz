## Source Set Role

Android UI screens live here. They should render Android-specific Compose layouts while consuming shared ViewModels, state, events, and effects from `sharedUI/src/commonJvmMain`.

Keep Android-only concerns here:
- Mobile route structure, Android layout density, Android-safe no-op capability affordances, and host wiring that should not leak into desktop screens.

Do not add business logic or agent/runtime calls to these composables; route user actions through the common ViewModel events.
