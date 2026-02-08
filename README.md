# Gigadesk 

A desktop Agent app to help with routines.

# Documentation

- [Agent engine](composeApp/src/jvmMain/kotlin/ru/gigadesk/agent/engine/README.md)
- [MCP Integration](composeApp/src/jvmMain/kotlin/ru/gigadesk/mcp/README.md)

# Developers notes
 
For Intellij IDEA you need next plugins:
- Kotlin Multiplatform;
- Compose Multiplatform;
- Compose Multiplatform for Desktop IDE support;

To launch preview rendering, press the desktop preview button near the composable.
       
Run tests with:
```bash
./gradlew :composeApp:cleanJvmTest :composeApp:jvmTest
```