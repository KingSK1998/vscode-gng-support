import type { Disposable } from "vscode";
import type { ErrorService } from "../errors/ErrorService";
import type { LanguageServerManager } from "../languageServer/LanguageServerManager";

export class RefactorRenameService implements Disposable {
  private _disposed = false;

  constructor(
    private readonly languageServerManager: LanguageServerManager,
    private readonly errorService: ErrorService
  ) {}

  get isDisposed(): boolean {
    return this._disposed;
  }

  dispose(): void {
    this._disposed = true;
  }
}
