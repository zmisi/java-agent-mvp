import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { TOOL_DEFINITIONS, TOOL_HANDLERS } from "./tools.js";

export async function startServer() {
  const server = new Server(
    { name: "java-agent-mcp", version: "0.1.0" },
    { capabilities: { tools: {} } },
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: TOOL_DEFINITIONS,
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const name = request.params.name;
    const handler = TOOL_HANDLERS[name];
    if (!handler) {
      return {
        content: [{ type: "text", text: JSON.stringify({ ok: false, error: `Unknown tool: ${name}` }) }],
        isError: true,
      };
    }
    try {
      return await handler(request.params.arguments ?? {});
    } catch (e) {
      return {
        content: [{ type: "text", text: JSON.stringify({ ok: false, error: e.message }) }],
        isError: true,
      };
    }
  });

  const transport = new StdioServerTransport();
  await server.connect(transport);
}
