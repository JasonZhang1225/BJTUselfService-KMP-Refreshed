/** Foreground observation only: polling never creates a school refresh task. */
export function createRefreshMonitor({ api, onStatus, onTask, onBusy, onError,
  storage, visible = () => true, setTimer = setTimeout, clearTimer = clearTimeout,
  makeKey = () => 'web_' + crypto.randomUUID().replaceAll('-', '') }) {
  const key = 'bjtuss.pending-refresh';
  let pending = null;
  try { pending = JSON.parse(storage?.getItem(key) || 'null'); } catch {}
  if (!pending || typeof pending.key !== 'string' || !/^web_[a-f0-9]{32}$/.test(pending.key)) pending = null;
  let task = null, state = null, timer = null, flight = null, revision = 0;
  let started = false, submitting = false, failures = 0, details = null;
  const active = value => value && ['queued', 'running'].includes(value.status);
  const busy = () => submitting || Boolean(active(task));
  function savePending(value) {
    pending = value;
    try { value ? storage?.setItem(key, JSON.stringify(value)) : storage?.removeItem(key); } catch {}
  }
  function publish(value) { task = value; onTask(value); onBusy(busy()); }
  function schedule(delay) {
    if (!started) return;
    if (timer != null) clearTimer(timer);
    timer = setTimer(() => { timer = null; void poll(); }, delay);
  }
  function receiveStatus(value) {
    state = value;
    const device = value.devices.find(d => !d.revokedAt);
    if (pending?.deviceId && device?.deviceId !== pending.deviceId) savePending(null);
    onStatus(value);
    const latest = value.latestRefreshTask;
    if (!submitting && latest && (!task || latest.requestId !== task.requestId || latest.status !== task.status)) {
      publish(latest);
    }
    if (latest && pending?.key === latest.idempotencyKey) savePending(null);
    if (!device && active(task)) publish({ ...task, status: 'revoked' });
    if (device && !latest && task && !submitting) { details = null; publish(null); }
  }
  async function poll() {
    if (flight) return flight;
    if (!visible() || submitting) { schedule(10000); return; }
    const expected = revision;
    flight = (async () => {
      try {
        const value = await api('status');
        if (expected !== revision || !started) return;
        receiveStatus(value);
        if (task && !value.queriesPaused && (active(task) || details !== task.requestId + ':' + task.status)) {
          const before = task;
          let result;
          try { result = await api('tasks/' + encodeURIComponent(before.requestId)); }
          catch (error) {
            if (error?.status !== 404) throw error;
            result = { ...before, status: 'unavailable' };
          }
          if (expected !== revision || !started) return;
          if (!result) publish({ ...before, status: 'unavailable' });
          else {
            details = result.requestId + ':' + result.status;
            publish(result);
            // Completion includes the upload; fetch again so source times cannot lag behind the badge.
            if (!active(result)) {
              const completed = await api('status');
              if (expected !== revision || !started) return;
              receiveStatus(completed);
            }
          }
        }
        failures = 0;
      } catch {
        if (expected === revision && started) {
          failures += 1;
          onError('连接暂时中断，正在自动重试；已提交的任务仍会继续，不会重复发起刷新。');
        }
      } finally {
        flight = null;
        schedule(failures ? Math.min(30000, 2000 * 2 ** Math.min(failures, 4)) : active(task) && !state?.queriesPaused ? 2000 : 10000);
      }
    })();
    return flight;
  }
  async function requestRefresh() {
    if (busy()) return task;
    submitting = true; revision += 1; onBusy(true);
    if (!pending) savePending({ key: makeKey(), deviceId: state?.devices.find(d => !d.revokedAt)?.deviceId });
    try {
      const result = await api('tasks', 'POST', { operation: 'refresh_all', idempotencyKey: pending.key, arguments: {} });
      publish(result); details = null; savePending(null); failures = 0;
      return result;
    } catch (error) {
      onError('暂未确认请求是否送达。可以重试，系统会沿用同一个请求，避免重复刷新。' + (error?.message ? ' ' + error.message : ''));
      return null;
    } finally {
      submitting = false; onBusy(busy()); schedule(0);
    }
  }
  return {
    start() { if (started) return; started = true; void poll(); },
    stop() { started = false; revision += 1; if (timer != null) clearTimer(timer); timer = null; },
    wake() { if (started && visible()) { if (timer != null) clearTimer(timer); timer = null; return poll(); } },
    poll, requestRefresh, busy,
  };
}
