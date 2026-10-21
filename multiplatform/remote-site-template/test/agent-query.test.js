import test from "node:test";
import assert from "node:assert/strict";
import { fixture } from "./helpers.js";
import { AgentQueryError, parseAgentQuery, runAgentQuery } from "../src/agent-query.js";

// 契约样例：KMP 端 AgentQueryContractTest 读取同一组文件，Kotlin 与 JS 引擎必须给出完全相同的结果。
const dataset = fixture("agent-query/dataset.json");
const queries = fixture("agent-query/queries.json");
const expected = fixture("agent-query/expected.json");

test("契约样例与期望输出一一对应", () => {
  assert.deepEqual(queries.map(q => q.name), expected.map(e => e.name));
});

for (const [index, q] of queries.entries()) {
  test(`契约：${q.name}`, () => {
    let actual;
    try {
      actual = { name: q.name, result: runAgentQuery(dataset.data, dataset.sources, parseAgentQuery(q.command, q.args), dataset.now, "cloud_snapshot") };
    } catch (error) {
      if (!(error instanceof AgentQueryError)) throw error;
      actual = { name: q.name, parseError: { code: error.code, message: error.message } };
    }
    assert.deepEqual(actual, expected[index]);
    assert.equal(Boolean(q.parseError), Boolean(actual.parseError));
  });
}

test("作业一次返回全部来源，00:00 截止写作前一天 24:00", () => {
  const result = runAgentQuery(dataset.data, dataset.sources, parseAgentQuery("homework", { status: "pending" }), dataset.now, "cloud_snapshot");
  assert.deepEqual([...new Set(result.items.map(i => i.source))].sort(), ["citel", "course_platform", "phyvlab"]);
  const citel = result.items.find(i => i.id === "citel:9265");
  assert.equal(citel.due, "2026-10-25（周日）24:00");
  assert.equal(citel.dueDate, "2026-10-25");
  assert.ok(!JSON.stringify(result).includes("2026-10-26"), "不应出现次日日期，避免被理解成 26 日或 25 日 0 点");
});
