import { equal, ok } from "node:assert/strict";
import { test } from "node:test";
import type { ExtensionContext } from "vscode";
import { getClientOptions } from "../services/languageServer/clientConfig";
import type { ConfigurationService } from "../services/workspace/ConfigurationService";
import { OutputChannelService } from "../services/workspace/OutputChannelService";

OutputChannelService.initialize({ subscriptions: [] } as unknown as ExtensionContext);

void test("R4-04: clientConfig includes yaml, json, properties, gsp, and groovy in documentSelector", () => {
  const config = {
    javaHome: "",
    completionDetail: "advanced",
    maxCompletionItems: 100,
    cacheEnabled: true,
    codeLensEnabled: true,
  } as ConfigurationService;

  const options = getClientOptions(config);
  ok(options.documentSelector);
  const selector = Array.isArray(options.documentSelector) ? options.documentSelector : [options.documentSelector];

  const languages = selector.map(s => (typeof s === "object" && s !== null && "language" in s ? s.language : ""));

  ok(languages.includes("groovy"), "Should include groovy selector");
  ok(languages.includes("gsp"), "Should include gsp selector");
  ok(languages.includes("properties"), "Should include properties selector");
  ok(languages.includes("yaml"), "Should include yaml selector");
  ok(languages.includes("json"), "Should include json selector");
});

void test("R4-04: clientConfig registers file watchers for configuration and i18n files", () => {
  const config = {
    javaHome: "",
    completionDetail: "advanced",
    maxCompletionItems: 100,
    cacheEnabled: true,
    codeLensEnabled: true,
  } as ConfigurationService;

  const options = getClientOptions(config);
  ok(options.synchronize?.fileEvents);
  const watchers = Array.isArray(options.synchronize.fileEvents)
    ? options.synchronize.fileEvents
    : [options.synchronize.fileEvents];
  equal(watchers.length, 3);
});
