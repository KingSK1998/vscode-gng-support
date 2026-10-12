import type { ExtensionContext } from "vscode";
import { ServiceContainer } from "../../core/container/ServiceContainer";

/**
 * @deprecated Use DashboardService from ServiceContainer instead.
 */
export class GrailsDashboard {
  public createOrShow(_context: ExtensionContext): void {
    try {
      const container = ServiceContainer.getInstance();
      container.dashboardService.openDashboard();
    } catch {
      // Container not yet initialized
    }
  }
}
