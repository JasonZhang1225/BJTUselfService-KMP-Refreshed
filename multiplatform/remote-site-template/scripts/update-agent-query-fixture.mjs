// 重新生成 fixtures/agent-query/expected.json（契约样例的期望输出）。
// 生成后必须人工审阅差异：KMP 端 AgentQueryContractTest 会用同一份样例校验 Kotlin 引擎。
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { AgentQueryError, parseAgentQuery, runAgentQuery } from "../src/agent-query.js";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "fixtures", "agent-query");
const dataset = JSON.parse(readFileSync(join(dir, "dataset.json"), "utf8"));
const queries = JSON.parse(readFileSync(join(dir, "queries.json"), "utf8"));
const expected = queries.map(({ name, command, args }) => {
  try {
    return { name, result: runAgentQuery(dataset.data, dataset.sources, parseAgentQuery(command, args), dataset.now, "cloud_snapshot") };
  } catch (error) {
    if (!(error instanceof AgentQueryError)) throw error;
    return { name, parseError: { code: error.code, message: error.message } };
  }
});
writeFileSync(join(dir, "expected.json"), JSON.stringify(expected, null, 2) + "\n");
console.log(`wrote ${expected.length} cases`);
