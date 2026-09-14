const { readdirSync } = require("node:fs");
const { join } = require("node:path");

// Run node:test suites in this process, including on hosts that cannot spawn a
// Node process per file. Editor integration tests require a separate VS Code host.
const Module = require("node:module");
const originalResolve = Module._resolveFilename;
Module._resolveFilename = function (request, parent, isMain, options) {
  if (request === "vscode") {
    return "vscode";
  }
  return originalResolve.call(this, request, parent, isMain, options);
};

const mockVscode = {
  Uri: {
    parse: str => ({
      toString: () => str,
      fsPath: str.replace(/^file:\/\/\/?/, ""),
    }),
    file: path => ({
      toString: () => `file:///${path.replace(/\\/g, "/")}`,
      fsPath: path,
    }),
  },
  workspace: {
    isTrusted: true,
    workspaceFolders: [],
    getWorkspaceFolder: uri => {
      const path = (uri.fsPath || uri.toString()).toLowerCase();
      return (
        mockVscode.workspace.workspaceFolders.find(f =>
          path.startsWith((f.uri.fsPath || f.uri.toString()).toLowerCase())
        ) || null
      );
    },
  },
  lm: {
    registerTool: (_name, _tool) => ({ dispose: () => {} }),
  },
  LanguageModelToolResult: class {
    constructor(content) {
      this.content = content;
    }
  },
  LanguageModelTextPart: class {
    constructor(value) {
      this.value = value;
    }
  },
  Disposable: class {
    constructor(callOnDispose) {
      this.callOnDispose = callOnDispose;
    }
    dispose() {
      if (this.callOnDispose) this.callOnDispose();
    }
  },
};

require.cache["vscode"] = {
  id: "vscode",
  filename: "vscode",
  loaded: true,
  exports: mockVscode,
};
const testDirectory = join(__dirname, "..", "client", "out", "test");
const tests = readdirSync(testDirectory).filter(name => name.endsWith(".unit.test.js")).sort();
if (tests.length === 0) throw new Error("No compiled client unit tests. Run npm run compile first.");
for (const file of tests) require(join(testDirectory, file));
require("./copy-server.test");
require("./language-server-manager.test");
require("./package.test");
