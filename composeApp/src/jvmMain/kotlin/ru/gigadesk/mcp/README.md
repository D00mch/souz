# MCP integration

Gigadesk can load external tools from MCP servers (stdio transport).

Configuration sources:
- `MCP_SERVERS_JSON` - JSON string with server definitions.
- `MCP_SERVERS_FILE` - path to a JSON file with server definitions.

Supported JSON format:
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/work"],
      "env": {},
      "cwd": "/path/to/work",
      "timeoutMillis": 30000
    }
  }
}
```

Loaded MCP tools are provided unconditionally by the graph runtime (not category-classified).

## Usage example

An example on how to setup the mcpNode betwen nodes A and B.

```kotlin
val nodesMCP: NodesMCP by di.instance()

val nodeA = someOtherNodeA()
val nodeB = someOtherNodeB()
val nodeAddMcpTools: Node<String, String> = nodesMCP.nodeProvideMcpTools("mcp-tools")

nodeA.edgeTo(nodeAddMcpTools)
nodeAddMcpTools.edgeTo(nodeB)
```