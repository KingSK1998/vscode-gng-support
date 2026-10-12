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

class Position {
  constructor(line, character) {
    this.line = line;
    this.character = character;
  }
  isBefore(other) {
    return this.line < other.line || (this.line === other.line && this.character < other.character);
  }
  isAfter(other) {
    return this.line > other.line || (this.line === other.line && this.character > other.character);
  }
  isEqual(other) {
    return this.line === other.line && this.character === other.character;
  }
}

class Range {
  constructor(start, end) {
    if (typeof start === "number") {
      this.start = new Position(start, end);
      this.end = new Position(arguments[2], arguments[3]);
    } else {
      this.start = start;
      this.end = end;
    }
  }
}

class TextEdit {
  constructor(range, newText) {
    this.range = range;
    this.newText = newText;
  }
}

class CompletionItem {
  constructor(label, kind) {
    this.label = label;
    this.kind = kind;
  }
}

class SnippetString {
  constructor(value) {
    this.value = value;
  }
}

class CodeLens {
  constructor(range, command) {
    this.range = range;
    this.command = command;
  }
}

class Location {
  constructor(uri, rangeOrPosition) {
    this.uri = uri;
    this.range = rangeOrPosition;
  }
}

class Diagnostic {
  constructor(range, message, severity) {
    this.range = range;
    this.message = message;
    this.severity = severity;
  }
}

class DocumentLink {
  constructor(range, target) {
    this.range = range;
    this.target = target;
  }
}

class ColorInformation {
  constructor(range, color) {
    this.range = range;
    this.color = color;
  }
}

class InlayHint {
  constructor(position, label, kind) {
    this.position = position;
    this.label = label;
    this.kind = kind;
  }
}

class CompletionList {
  constructor(items, isIncomplete) {
    this.items = items || [];
    this.isIncomplete = isIncomplete || false;
  }
}

class CodeAction {
  constructor(title, kind) {
    this.title = title;
    this.kind = kind;
  }
}

const CodeActionKind = {
  QuickFix: "quickfix",
  Refactor: "refactor",
  RefactorExtract: "refactor.extract",
  RefactorInline: "refactor.inline",
  RefactorRewrite: "refactor.rewrite",
  Source: "source",
  SourceOrganizeImports: "source.organizeImports",
  SourceFixAll: "source.fixAll",
};

class SymbolInformation {
  constructor(name, kind, containerName, location) {
    this.name = name;
    this.kind = kind;
    this.containerName = containerName;
    this.location = location;
  }
}

class DocumentSymbol {
  constructor(name, detail, kind, range, selectionRange) {
    this.name = name;
    this.detail = detail;
    this.kind = kind;
    this.range = range;
    this.selectionRange = selectionRange;
    this.children = [];
  }
}

class FoldingRange {
  constructor(start, end, kind) {
    this.start = start;
    this.end = end;
    this.kind = kind;
  }
}

class SelectionRange {
  constructor(range, parent) {
    this.range = range;
    this.parent = parent;
  }
}

class CallHierarchyItem {
  constructor(kind, name, detail, uri, range, selectionRange) {
    this.kind = kind;
    this.name = name;
    this.detail = detail;
    this.uri = uri;
    this.range = range;
    this.selectionRange = selectionRange;
  }
}

class TypeHierarchyItem {
  constructor(kind, name, detail, uri, range, selectionRange) {
    this.kind = kind;
    this.name = name;
    this.detail = detail;
    this.uri = uri;
    this.range = range;
    this.selectionRange = selectionRange;
  }
}

class SemanticTokens {
  constructor(data, resultId) {
    this.data = data;
    this.resultId = resultId;
  }
}

class CancellationError extends Error {
  constructor() {
    super("Canceled");
    this.name = "CancellationError";
  }
}

class TreeItem {
  constructor(label, collapsibleState) {
    this.label = label;
    this.collapsibleState = collapsibleState;
  }
}

const TreeItemCollapsibleState = {
  None: 0,
  Collapsed: 1,
  Expanded: 2,
};

class ThemeIcon {
  constructor(id, color) {
    this.id = id;
    this.color = color;
  }
}

class EventEmitter {
  constructor() {
    this.event = () => ({ dispose: () => {} });
  }
  fire() {}
  dispose() {}
}

class TestMessage {
  constructor(message) {
    this.message = message;
  }
}

class CancellationTokenSource {
  constructor() {
    this.token = {
      isCancellationRequested: false,
      onCancellationRequested: () => ({ dispose: () => {} }),
    };
  }
  cancel() {
    this.token.isCancellationRequested = true;
  }
  dispose() {}
}

class MockTestItemCollection {
  constructor() {
    this._map = new Map();
  }
  get size() {
    return this._map.size;
  }
  get(id) {
    return this._map.get(id);
  }
  add(item) {
    this._map.set(item.id, item);
  }
  delete(id) {
    return this._map.delete(id);
  }
  forEach(callback) {
    this._map.forEach(callback);
  }
  replace(items) {
    this._map.clear();
    for (const item of items) {
      this._map.set(item.id, item);
    }
  }
  [Symbol.iterator]() {
    return this._map.values();
  }
}

class MockTestItem {
  constructor(id, label, uri) {
    this.id = id;
    this.label = label;
    this.uri = uri;
    this.children = new MockTestItemCollection();
  }
}

const mockTestController = (id, label) => ({
  id,
  label,
  items: new MockTestItemCollection(),
  createTestItem: (itemId, itemLabel, uri) => new MockTestItem(itemId, itemLabel, uri),
  createRunProfile: (name, kind, handler) => ({
    name,
    kind,
    dispose: () => {},
  }),
  createTestRun: request => ({
    name: "run",
    started: () => {},
    passed: () => {},
    failed: (_item, _msg) => {},
    skipped: () => {},
    errored: () => {},
    end: () => {},
    appendOutput: () => {},
  }),
  resolveHandler: null,
  dispose: () => {},
});

const StatusBarAlignment = {
  Left: 1,
  Right: 2,
};

const mockVscode = {
  Position,
  Range,
  TextEdit,
  CompletionItem,
  CompletionItemKind: {
    Property: 9,
    Function: 2,
    Value: 11,
    File: 16,
  },
  SnippetString,
  CompletionList,
  CodeLens,
  CodeAction,
  CodeActionKind,
  Location,
  Diagnostic,
  DocumentLink,
  ColorInformation,
  InlayHint,
  SymbolInformation,
  DocumentSymbol,
  FoldingRange,
  SelectionRange,
  CallHierarchyItem,
  TypeHierarchyItem,
  SemanticTokens,
  CancellationError,
  CancellationTokenSource,
  TestMessage,
  TreeItem,
  TreeItemCollapsibleState,
  ThemeIcon,
  EventEmitter,
  StatusBarAlignment,
  TestRunProfileKind: {
    Run: 1,
    Debug: 2,
    Coverage: 3,
  },
  tests: {
    createTestController: (id, label) => mockTestController(id, label),
  },
  extensions: {
    getExtension: id => ({
      id,
      isActive: true,
      exports: {},
    }),
  },
  window: {
    createStatusBarItem: (alignment, priority) => ({
      alignment,
      priority,
      text: "",
      tooltip: "",
      command: "",
      color: "",
      backgroundColor: undefined,
      show: () => {},
      hide: () => {},
      dispose: () => {},
    }),
    createOutputChannel: name => ({
      name,
      append: () => {},
      appendLine: () => {},
      clear: () => {},
      show: () => {},
      hide: () => {},
      dispose: () => {},
    }),
    showInformationMessage: async () => undefined,
    showWarningMessage: async () => undefined,
    showErrorMessage: async () => undefined,
  },
  debug: {
    onDidTerminateDebugSession: () => ({ dispose: () => {} }),
    startDebugging: async () => true,
    stopDebugging: async () => {},
  },
  commands: {
    executeCommand: async () => null,
  },
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
    getConfiguration: (section, scope) => ({
      get: (key, defaultValue) => defaultValue,
      has: key => false,
      inspect: key => undefined,
      update: async () => {},
    }),
    createFileSystemWatcher: () => ({ dispose: () => {} }),
    onDidChangeTextDocument: () => ({ dispose: () => {} }),
    onDidCloseTextDocument: () => ({ dispose: () => {} }),
    onDidChangeWorkspaceFolders: () => ({ dispose: () => {} }),
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
