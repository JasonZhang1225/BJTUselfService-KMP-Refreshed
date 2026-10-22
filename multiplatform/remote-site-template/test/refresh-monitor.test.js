import test from 'node:test';
import assert from 'node:assert/strict';
import { createRefreshMonitor } from '../src/refresh-monitor.js';
import { page } from '../src/page.js';
import { createSite, pairDevice, snapshotFor } from './helpers.js';

async function setup(t, options = {}) {
  const site = createSite(), device = await pairDevice(site);
  t.after(() => site.env.DB.raw.close());
  const calls = [], tasks = [], states = [], errors = [], timers = new Map(), saved = new Map();
  let visible = true, timerId = 0;
  const api = async (path, method = 'GET', body) => {
    calls.push({ path, method, body });
    const response = await site.call(method, '/api/v1/owner/' + path, { owner: true, body });
    if (response.status >= 400) { const error = Error(response.body.error?.message); error.status = response.status; throw error; }
    return response.body;
  };
  const monitor = createRefreshMonitor({ api: options.wrap ? options.wrap(api) : api,
    onStatus: value => states.push(value), onTask: value => tasks.push(value), onBusy() {}, onError: value => errors.push(value),
    storage: { getItem: k => saved.get(k), setItem: (k, v) => saved.set(k, v), removeItem: k => saved.delete(k) },
    setTimer: (callback, ms) => { const id = ++timerId; timers.set(id, { callback, ms }); return id; },
    clearTimer: id => timers.delete(id), visible: () => visible, makeKey: () => 'web_' + 'a'.repeat(32),
  });
  t.after(() => monitor.stop());
  if (options.beforeStart) await options.beforeStart({ site, device, api });
  monitor.start(); await monitor.poll();
  return { site, device, monitor, calls, tasks, states, errors, saved, timers, api, setVisible: value => { visible = value; } };
}

test('页面内嵌脚本可解析，刷新状态区具备无障碍播报', () => {
  const script = page.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
  assert.doesNotThrow(() => new Function(script));
  assert.match(page, /id="refresh-progress"[^>]*aria-live="polite"/);
});

test('真实队列：排队、领取、完成及快照时间自动更新；普通轮询不创建任务', async t => {
  const h = await setup(t);
  const task = await h.monitor.requestRefresh();
  assert.equal(h.tasks.at(-1).status, 'queued');
  await h.monitor.poll();
  assert.equal(h.tasks.at(-1).status, 'queued');
  const poll = await h.site.call('POST', '/api/v1/device/poll', { token: h.device.deviceToken });
  await h.monitor.poll();
  assert.equal(h.tasks.at(-1).status, 'running');
  h.site.clock.now += 20;
  const snapshot = snapshotFor(h.device);
  snapshot.sources.courses.fetchedAt = h.site.clock.now;
  await h.site.call('PUT', '/api/v1/device/snapshot', { token: h.device.deviceToken, body: snapshot });
  await h.site.call('POST', '/api/v1/device/tasks/' + task.requestId + '/result', { token: h.device.deviceToken,
    body: { leaseId: poll.body.task.leaseId, status: 'succeeded', result: { snapshotSequence: snapshot.sequence } } });
  await h.monitor.poll();
  assert.equal(h.tasks.at(-1).status, 'succeeded');
  assert.equal(h.states.at(-1).snapshot.sources.courses.fetchedAt, h.site.clock.now);
  assert.equal(h.monitor.busy(), false);
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 1);
});

test('刷新期间重复点击只创建一个任务；页面恢复时能发现其他入口发起的刷新', async t => {
  const h = await setup(t);
  const task = await h.monitor.requestRefresh();
  await h.monitor.requestRefresh();
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 1);
  assert.equal(h.tasks.at(-1).requestId, task.requestId);
  const status = await h.api('status');
  assert.equal(status.latestRefreshTask.requestId, task.requestId);
  assert.equal(status.latestRefreshTask.result, undefined, '状态接口只暴露任务元数据');
  assert.equal(status.latestRefreshTask.arguments, undefined);
  assert.equal(status.latestRefreshTask.accountBindingId, undefined);
});

test('POST 已送达但响应丢失，重试复用幂等键，不新增刷新', async t => {
  let first = true;
  const h = await setup(t, { wrap: api => async (...args) => {
    const result = await api(...args);
    if (args[1] === 'POST' && first) { first = false; throw Error('模拟响应丢失'); }
    return result;
  } });
  assert.equal(await h.monitor.requestRefresh(), null);
  assert.equal(h.saved.size, 1);
  const result = await h.monitor.requestRefresh();
  assert.equal(result.created, false);
  const posts = h.calls.filter(c => c.method === 'POST');
  assert.equal(posts[0].body.idempotencyKey, posts[1].body.idempotencyKey);
  assert.equal(h.site.env.DB.raw.prepare('SELECT count(*) AS n FROM tasks').get().n, 1);
});

test('页面隐藏后暂停读取，回到前台继续；网络失败退避且不重复刷新', async t => {
  let broken = false;
  const h = await setup(t, { wrap: api => (...args) => { if (broken) return Promise.reject(Error('offline')); return api(...args); } });
  const before = h.calls.length;
  h.setVisible(false); await h.monitor.poll();
  assert.equal(h.calls.length, before);
  h.setVisible(true); broken = true; await h.monitor.wake();
  assert.match(h.errors.at(-1), /自动重试/);
  assert.ok([...h.timers.values()].some(t => t.ms >= 4000));
  broken = false; await h.monitor.wake();
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 0);
});

test('任务过期或清除会结束等待；新的设备绑定不能看到旧设备刷新元数据', async t => {
  const h = await setup(t);
  const task = await h.monitor.requestRefresh();
  h.site.clock.now += 301;
  await h.monitor.poll();
  assert.equal(h.tasks.at(-1).status, 'expired');
  assert.equal(h.monitor.busy(), false);
  await h.site.call('DELETE', '/api/v1/owner/data', { owner: true });
  // Re-open the page against a deleted task record.
  h.site.env.DB.raw.prepare("INSERT INTO tasks (request_id,idempotency_key,device_id,account_binding_id,operation,arguments_json,created_at,expires_at,status) VALUES (?,?,?,?,?,?,?,?,?)")
    .run('req_other00000001','idem_other00001',h.device.deviceId,h.device.accountBindingId,'refresh_all','{}',h.site.clock.now,h.site.clock.now+300,'queued');
  await h.monitor.poll();
  await pairDevice(h.site, { accountBindingId: 'bind_another0001' });
  const status = await h.api('status');
  assert.equal(status.latestRefreshTask, null);
  assert.notEqual(task.requestId, 'req_other00000001');
});

test('进行中的旧状态响应不能覆盖刚提交的新请求', async t => {
  let release, hold = false;
  const h = await setup(t, { wrap: api => async (...args) => {
    const value = await api(...args);
    if (args[0] === 'status' && hold) { hold = false; await new Promise(resolve => { release = resolve; }); }
    return value;
  } });
  hold = true;
  const older = h.monitor.poll();
  while (!release) await new Promise(resolve => setImmediate(resolve));
  const task = await h.monitor.requestRefresh();
  release(); await older;
  assert.equal(h.tasks.at(-1).requestId, task.requestId);
  assert.equal(h.tasks.at(-1).status, 'queued');
});

test('重新打开页面可恢复已有刷新状态，而不再发送刷新请求', async t => {
  const h = await setup(t, { beforeStart: async ({ api }) => {
    await api('tasks', 'POST', { operation: 'refresh_all', idempotencyKey: 'another_entry_001', arguments: {} });
  } });
  assert.equal(h.tasks.at(-1).status, 'queued');
  assert.equal(h.monitor.busy(), true);
  await h.monitor.poll();
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 1);
});

for (const status of ['partial', 'failed']) {
  test(status + ' 如实显示结果且结束等待，不重复发送学校刷新', async t => {
    const h = await setup(t);
    const task = await h.monitor.requestRefresh();
    const poll = await h.site.call('POST', '/api/v1/device/poll', { token: h.device.deviceToken });
    await h.site.call('POST', '/api/v1/device/tasks/' + task.requestId + '/result', { token: h.device.deviceToken,
      body: { leaseId: poll.body.task.leaseId, status, result: { snapshotUploaded: false }, error: { code: 'needs_user_action', message: '请在电脑上登录' } } });
    await h.monitor.poll();
    assert.equal(h.tasks.at(-1).status, status);
    assert.equal(h.tasks.at(-1).result.data.snapshotUploaded, false);
    assert.equal(h.tasks.at(-1).result.error.message, '请在电脑上登录');
    assert.equal(h.monitor.busy(), false);
    assert.equal(h.calls.filter(c => c.method === 'POST').length, 1);
  });
}
