# MCP integration

Gigadesk can load external tools from MCP servers over:
- `stdio`
- `http` (streamable HTTP / JSON-RPC)

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
    },
    "notion": {
      "transport": "http",
      "url": "https://mcp.notion.com/mcp",
      "timeoutMillis": 30000
    }
  }
}
```

For HTTP servers protected by OAuth (including Notion MCP), Gigadesk:
- discovers OAuth endpoints automatically (Protected Resource Metadata -> Authorization Server Metadata),
- opens browser login automatically,
- stores/refreshes OAuth tokens for next runs.

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