/** 手机没有后台心跳；最近联系不能当成当前在线承诺。各设备只关联自己的快照。 */
export function deviceStatusCards(state) {
  const now = state.serverTime ?? Math.floor(Date.now() / 1000);
  return (state.devices ?? []).filter(d => !d.revokedAt).map(device => {
    const mobile = device.deviceKind === 'mobile';
    const recent = device.lastSeenAt != null && now - device.lastSeenAt >= 0 && now - device.lastSeenAt <= 120;
    const snapshot = (state.snapshots ?? []).find(s => s.deviceId === device.deviceId);
    return {
      ...device,
      presence: mobile ? (recent ? '刚刚联系 · 不提供持续在线状态' : '按需上传 · 不提供持续在线状态')
        : (recent ? '在线' : '离线或暂未联系'),
      behavior: mobile ? '手机 / 平板 · 刷新后上传，不轮询' : '电脑 · 定时同步并轮询即时任务',
      receivedAt: snapshot?.receivedAt ?? null,
      generatedAt: snapshot?.generatedAt ?? null,
      taskTarget: !mobile && device.deviceId === state.taskDeviceId,
    };
  });
}
