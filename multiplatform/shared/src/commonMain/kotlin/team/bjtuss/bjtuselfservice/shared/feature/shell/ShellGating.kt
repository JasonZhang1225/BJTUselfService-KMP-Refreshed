package team.bjtuss.bjtuselfservice.shared.feature.shell

/**
 * 物理在线列表与首页日程共用同一个模型，因此自动同步只能由一个稳定的宿主启动。
 *
 * 旧的共享壳只有一个 Compose 实例，`forcedRouteId == null` 就足够了。iOS Liquid
 * 壳则为每个一级 Tab 建一个宿主；它们都会执行壳层副作用，如果只判断
 * `forcedRouteId != null`，首页也会被排除，物理在线便只会在用户点进页面后才同步。
 */
internal fun shouldStartPhyVlabAutoSync(
    forcedRouteId: String?,
    nativeTabBarEnabled: Boolean,
): Boolean = forcedRouteId == null ||
    (nativeTabBarEnabled && forcedRouteId == AppSection.HOME.name)
