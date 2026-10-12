import * as net from "net";
import { GrailsMcpServer, type AnalysisBackend } from "./GrailsMcpServer";

/**
 * Socket-backed analysis backend connecting to the Grails LSP server over TCP JSON-RPC.
 */
export class SocketLspAnalysisBackend implements AnalysisBackend {
  constructor(
    private readonly host = "127.0.0.1",
    private readonly port = 5007
  ) {}

  async executeCommand<T = unknown>(
    command: string,
    args: unknown[],
    abortSignal?: AbortSignal
  ): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      if (abortSignal?.aborted) {
        return reject(new Error("Operation cancelled before connecting."));
      }

      const socket = net.createConnection({ host: this.host, port: this.port }, () => {
        const reqId = Date.now();
        const lspRequest = {
          jsonrpc: "2.0",
          id: reqId,
          method: "workspace/executeCommand",
          params: {
            command,
            arguments: args,
          },
        };

        const body = JSON.stringify(lspRequest);
        const message = `Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`;
        socket.write(message);

        let buffer = "";
        const onData = (chunk: Buffer) => {
          buffer += chunk.toString("utf8");
          const headerEnd = buffer.indexOf("\r\n\r\n");
          if (headerEnd !== -1) {
            const match = buffer.match(/Content-Length:\s*(\d+)/i);
            if (match) {
              const len = parseInt(match[1], 10);
              const bodyStart = headerEnd + 4;
              if (buffer.length >= bodyStart + len) {
                const rawResp = buffer.slice(bodyStart, bodyStart + len);
                socket.end();
                try {
                  const parsed: unknown = JSON.parse(rawResp);
                  const parsedObj = parsed as {
                    error?: { message?: string };
                    result?: unknown;
                  } | null;
                  if (parsedObj?.error) {
                    reject(new Error(parsedObj.error.message ?? "LSP command error"));
                  } else {
                    resolve((parsedObj?.result ?? null) as T);
                  }
                } catch (e) {
                  reject(e instanceof Error ? e : new Error(String(e)));
                }
              }
            }
          }
        };

        socket.on("data", onData);
      });

      socket.on("error", err => {
        reject(
          new Error(
            `Failed to connect to Grails Language Server at ${this.host}:${this.port}. Is the language server running? Details: ${err.message}`
          )
        );
      });

      if (abortSignal) {
        abortSignal.addEventListener("abort", () => {
          socket.destroy();
          reject(new Error("Operation cancelled."));
        });
      }
    });
  }
}

export function startMcpServer(backend?: AnalysisBackend): GrailsMcpServer {
  const host = process.env.GRAILS_LSP_HOST ?? "127.0.0.1";
  const port = parseInt(process.env.GRAILS_LSP_PORT ?? "5007", 10);
  const effectiveBackend = backend ?? new SocketLspAnalysisBackend(host, port);

  const server = new GrailsMcpServer(effectiveBackend, process.stdin, process.stdout);

  const cleanup = () => {
    server.dispose();
    process.exit(0);
  };

  process.on("SIGINT", cleanup);
  process.on("SIGTERM", cleanup);

  return server;
}

if (require.main === module) {
  startMcpServer();
}
